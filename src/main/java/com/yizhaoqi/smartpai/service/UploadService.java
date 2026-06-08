package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.*;
import io.minio.http.Method;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final Logger logger = LoggerFactory.getLogger(UploadService.class);
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private MinioClient minioClient;
    @Autowired
    private FileUploadRepository fileUploadRepository;
    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private FileTypeValidationService fileTypeValidationService;

    @Autowired
    private String minioPublicUrl;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 上传文件分片
     */
    public void uploadChunk(String fileMd5, int chunkIndex, long totalSize, String fileName,
                           MultipartFile file, String orgTag, boolean isPublic, String userId) throws IOException {
        String fileType = getFileType(fileName);
        String contentType = file.getContentType();

        logger.info("[uploadChunk] 开始上传分片 => fileMd5: {}, chunkIndex: {}, totalSize: {}, fileName: {}, fileType: {}, contentType: {}, fileSize: {}, orgTag: {}, isPublic: {}, userId: {}",
                   fileMd5, chunkIndex, totalSize, fileName, fileType, contentType, file.getSize(), orgTag, isPublic, userId);

        if (chunkIndex == 0) {
            FileTypeValidationService.FileTypeValidationResult validationResult =
                    fileTypeValidationService.validateFileType(fileName);
            if (!validationResult.isValid()) {
                throw new FileValidationException(
                        validationResult.getMessage(),
                        validationResult.getFileType(),
                        fileTypeValidationService.getSupportedFileTypes()
                );
            }
        }

        try {
            boolean fileExists = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId).isPresent();
            logger.debug("检查文件记录是否存在 => fileMd5: {}, fileName: {}, fileType: {}, exists: {}", fileMd5, fileName, fileType, fileExists);

            if (!fileExists) {
                logger.info("创建新的文件上传记录 => fileMd5: {}, fileName: {}, fileType: {}, totalSize: {}, userId: {}, orgTag: {}, isPublic: {}",
                          fileMd5, fileName, fileType, totalSize, userId, orgTag, isPublic);
                FileUpload fileUpload = new FileUpload();
                fileUpload.setFileMd5(fileMd5);
                fileUpload.setFileName(fileName);
                fileUpload.setTotalSize(totalSize);
                fileUpload.setStatus(0);
                fileUpload.setUserId(userId);
                fileUpload.setOrgTag(orgTag);
                fileUpload.setPublic(isPublic);
                try {
                    fileUploadRepository.save(fileUpload);
                    logger.info("文件上传记录保存成功 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
                } catch (Exception e) {
                    logger.error("文件上传记录保存失败 => fileMd5: {}, fileName: {}, fileType: {}, 错误: {}", fileMd5, fileName, fileType, e.getMessage(), e);
                    throw new RuntimeException("文件上传记录保存失败: " + e.getMessage(), e);
                }
            }

            boolean chunkUploaded = isChunkUploaded(fileMd5, chunkIndex);
            logger.debug("检查分片是否已上传到Redis => fileMd5: {}, fileName: {}, chunkIndex: {}, isUploaded: {}",
                      fileMd5, fileName, chunkIndex, chunkUploaded);
            boolean chunkInfoExists = false;
            try {
                List<ChunkInfo> chunkInfos = chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc(fileMd5);
                chunkInfoExists = chunkInfos.stream()
                    .anyMatch(chunk -> chunk.getChunkIndex() == chunkIndex);
                logger.debug("检查分片信息是否已存在于数据库 => fileMd5: {}, fileName: {}, chunkIndex: {}, exists: {}",
                          fileMd5, fileName, chunkIndex, chunkInfoExists);
            } catch (Exception e) {
                logger.warn("查询分片信息数据库异常 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}",
                          fileMd5, fileName, chunkIndex, e.getMessage(), e);
                chunkInfoExists = false;
            }

            String chunkMd5 = null;
            String storagePath = null;

            if (chunkUploaded) {
                logger.warn("分片在Redis中已标记为已上传 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
                if (!chunkInfoExists) {
                    logger.info("Redis标记已上传但数据库无记录，需验证MinIO中是否存在 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                    byte[] fileBytes = file.getBytes();
                    chunkMd5 = DigestUtils.md5Hex(fileBytes);
                    storagePath = "chunks/" + fileMd5 + "/" + chunkIndex;
                    try {
                        StatObjectResponse stat = minioClient.statObject(
                            StatObjectArgs.builder()
                                .bucket(bucketName)
                                .object(storagePath)
                                .build()
                        );
                        logger.info("MinIO中分片对象已存在 => fileMd5: {}, fileName: {}, chunkIndex: {}, path: {}, size: {}",
                                  fileMd5, fileName, chunkIndex, storagePath, stat.size());
                    } catch (Exception e) {
                        logger.warn("MinIO中分片对象不存在，需要重新上传 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}",
                                  fileMd5, fileName, chunkIndex, e.getMessage());
                        chunkUploaded = false;
                    }
                } else {
                    logger.info("分片已上传且数据库记录存在，跳过 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                    return;
                }
            }

            if (!chunkUploaded) {
                logger.debug("开始计算分片MD5 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                byte[] fileBytes = file.getBytes();
                chunkMd5 = DigestUtils.md5Hex(fileBytes);
                logger.debug("分片MD5计算完成 => fileMd5: {}, fileName: {}, chunkIndex: {}, chunkMd5: {}",
                           fileMd5, fileName, chunkIndex, chunkMd5);
                storagePath = "chunks/" + fileMd5 + "/" + chunkIndex;
                logger.debug("准备上传分片到MinIO => fileName: {}, path: {}", fileName, storagePath);

                try {
                    logger.info("开始上传分片到MinIO => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, bucket: {}, path: {}, size: {}, contentType: {}",
                              fileMd5, fileName, fileType, chunkIndex, bucketName, storagePath, file.getSize(), contentType);

                    PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storagePath)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build();

                    minioClient.putObject(putObjectArgs);
                    logger.info("分片上传到MinIO成功 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
                } catch (Exception e) {
                    logger.error("分片上传到MinIO失败 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, 异常类型: {}, 错误: {}",
                              fileMd5, fileName, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);
                    if (e instanceof io.minio.errors.ErrorResponseException) {
                        io.minio.errors.ErrorResponseException ere = (io.minio.errors.ErrorResponseException) e;
                        logger.error("MinIO错误详情 => fileName: {}, code: {}, message: {}, resource: {}, requestId: {}",
                                 fileName, ere.errorResponse().code(), ere.errorResponse().message(),
                                 ere.errorResponse().resource(), ere.errorResponse().requestId());
                    }

                    throw new RuntimeException("分片上传到MinIO失败: " + e.getMessage(), e);
                }
                try {
                    logger.debug("标记分片为已上传状态 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                    markChunkUploaded(fileMd5, chunkIndex);
                    logger.debug("分片标记为已上传成功 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                } catch (Exception e) {
                    logger.error("标记分片上传状态失败 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}",
                              fileMd5, fileName, chunkIndex, e.getMessage(), e);
                }
            }

            if (!chunkInfoExists && chunkMd5 != null && storagePath != null) {
                try {
                    logger.debug("保存分片信息到数据库 => fileMd5: {}, fileName: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                              fileMd5, fileName, chunkIndex, chunkMd5, storagePath);
                    saveChunkInfo(fileMd5, chunkIndex, chunkMd5, storagePath);
                    logger.info("分片信息保存成功 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                } catch (Exception e) {
                    logger.error("保存分片信息到数据库失败 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}",
                              fileMd5, fileName, chunkIndex, e.getMessage(), e);
                    throw new RuntimeException("保存分片信息失败: " + e.getMessage(), e);
                }
            }

            logger.info("分片上传处理完成 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
        } catch (Exception e) {
            logger.error("分片上传处理异常 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, 异常类型: {}, 错误: {}",
                       fileMd5, fileName, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 根据文件名获取文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        switch (extension) {
            case "pdf": return "PDF";
            case "doc": case "docx": return "Word";
            case "xls": case "xlsx": return "Excel";
            case "ppt": case "pptx": return "PowerPoint";
            case "txt": return "Text";
            case "md": return "Markdown";
            case "jpg": case "jpeg": return "JPEG Image";
            case "png": return "PNG Image";
            case "gif": return "GIF Image";
            case "bmp": return "BMP Image";
            case "svg": return "SVG Image";
            case "mp4": return "MP4 Video";
            case "avi": return "AVI Video";
            case "mov": return "MOV Video";
            case "wmv": return "WMV Video";
// PLACEHOLDER_FILETYPE_CONTINUE
            case "mp3": return "MP3 Audio";
            case "wav": return "WAV Audio";
            case "flac": return "FLAC Audio";
            case "zip": return "ZIP Archive";
            case "rar": return "RAR Archive";
            case "7z": return "7Z Archive";
            case "tar": return "TAR Archive";
            case "gz": return "GZ Archive";
            case "json": return "JSON";
            case "xml": return "XML";
            case "csv": return "CSV";
            case "html": case "htm": return "HTML";
            case "css": return "CSS";
            case "js": return "JavaScript";
            case "java": return "Java";
            case "py": return "Python";
            case "cpp": case "c": return "C/C++";
            case "sql": return "SQL";
            default: return extension.toUpperCase() + " File";
        }
    }

    /**
     * 检查分片是否已上传（通过Redis bitmap）
     */
    public boolean isChunkUploaded(String fileMd5, int chunkIndex) {
        logger.debug("检查分片上传状态 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        try {
            if (chunkIndex < 0) {
                logger.error("分片索引不合法 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + fileMd5;
            boolean isUploaded = redisTemplate.opsForValue().getBit(redisKey, chunkIndex);
            logger.debug("分片上传状态查询结果 => fileMd5: {}, chunkIndex: {}, isUploaded: {}",
                      fileMd5, chunkIndex, isUploaded);
            return isUploaded;
        } catch (Exception e) {
            logger.error("检查分片上传状态异常 => fileMd5: {}, chunkIndex: {}, 错误: {}",
                      fileMd5, chunkIndex, e.getMessage(), e);
            return false;
        }
    }
// PLACEHOLDER_MARK_CHUNK

    /**
     * 在Redis bitmap中标记分片为已上传
     */
    public void markChunkUploaded(String fileMd5, int chunkIndex) {
        logger.debug("标记分片为已上传 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        try {
            if (chunkIndex < 0) {
                logger.error("分片索引不合法 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + fileMd5;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, true);
            logger.debug("分片标记成功 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        } catch (Exception e) {
            logger.error("标记分片上传状态失败 => fileMd5: {}, chunkIndex: {}, 错误: {}",
                      fileMd5, chunkIndex, e.getMessage(), e);
            throw new RuntimeException("Failed to mark chunk as uploaded", e);
        }
    }

    /**
     * 删除Redis中的文件上传标记
     */
    public void deleteFileMark(String fileMd5) {
        logger.debug("删除文件上传标记 => fileMd5: {}", fileMd5);
        try {
            String redisKey = "upload:" + fileMd5;
            redisTemplate.delete(redisKey);
            logger.info("文件上传标记删除成功 => fileMd5: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文件上传标记失败 => fileMd5: {}, 错误: {}", fileMd5, e.getMessage(), e);
            throw new RuntimeException("Failed to delete file mark", e);
        }
    }

    /**
     * 获取已上传的分片索引列表
     */
    public List<Integer> getUploadedChunks(String fileMd5) {
        logger.info("获取已上传分片列表 => fileMd5: {}", fileMd5);
        List<Integer> uploadedChunks = new ArrayList<>();
        try {
            int totalChunks = getTotalChunks(fileMd5);
            logger.debug("获取总分片数 => fileMd5: {}, totalChunks: {}", fileMd5, totalChunks);

            if (totalChunks == 0) {
                logger.warn("总分片数为0 => fileMd5: {}", fileMd5);
                return uploadedChunks;
            }
// PLACEHOLDER_GET_UPLOADED_CHUNKS
            String redisKey = "upload:" + fileMd5;
            byte[] bitmapData = redisTemplate.execute((RedisCallback<byte[]>) connection -> {
                return connection.get(redisKey.getBytes());
            });

            if (bitmapData == null) {
                logger.info("Redis中无该文件的bitmap数据 => fileMd5: {}", fileMd5);
                return uploadedChunks;
            }
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                if (isBitSet(bitmapData, chunkIndex)) {
                    uploadedChunks.add(chunkIndex);
                }
            }

            logger.info("已上传分片查询完成 => fileMd5: {}, uploaded: {}, totalChunks: {}",
                      fileMd5, uploadedChunks.size(), totalChunks);
            return uploadedChunks;
        } catch (Exception e) {
            logger.error("获取已上传分片列表失败 => fileMd5: {}, 错误: {}", fileMd5, e.getMessage(), e);
            throw new RuntimeException("Failed to get uploaded chunks", e);
        }
    }

    /**
     * 检查bitmap中指定位是否为1
     */
    private boolean isBitSet(byte[] bitmapData, int bitIndex) {
        try {
            int byteIndex = bitIndex / 8;
            int bitPosition = 7 - (bitIndex % 8);

            if (byteIndex >= bitmapData.length) {
                return false;
            }

            return (bitmapData[byteIndex] & (1 << bitPosition)) != 0;
        } catch (Exception e) {
            logger.error("检查bitmap位状态异常 => bitIndex: {}, 错误: {}", bitIndex, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 计算文件总分片数
     */
    public int getTotalChunks(String fileMd5) {
        logger.info("计算文件总分片数 => fileMd5: {}", fileMd5);
        try {
            Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5OrderByIdAsc(fileMd5);
// PLACEHOLDER_TOTAL_CHUNKS

            if (fileUpload.isEmpty()) {
                logger.warn("文件上传记录不存在，无法计算分片数 => fileMd5: {}", fileMd5);
                return 0;
            }

            long totalSize = fileUpload.get().getTotalSize();
            int chunkSize = 5 * 1024 * 1024;
            int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);

            logger.info("总分片数计算完成 => fileMd5: {}, totalSize: {}, chunkSize: {}, totalChunks: {}",
                      fileMd5, totalSize, chunkSize, totalChunks);
            return totalChunks;
        } catch (Exception e) {
            logger.error("计算文件总分片数失败 => fileMd5: {}, 错误: {}", fileMd5, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate total chunks", e);
        }
    }

    /**
     * 保存分片信息到数据库
     */
    private void saveChunkInfo(String fileMd5, int chunkIndex, String chunkMd5, String storagePath) {
        logger.debug("保存分片信息 => fileMd5: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                   fileMd5, chunkIndex, chunkMd5, storagePath);
        try {
            ChunkInfo chunkInfo = new ChunkInfo();
            chunkInfo.setFileMd5(fileMd5);
            chunkInfo.setChunkIndex(chunkIndex);
            chunkInfo.setChunkMd5(chunkMd5);
            chunkInfo.setStoragePath(storagePath);

            chunkInfoRepository.save(chunkInfo);
            logger.debug("分片信息保存成功 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        } catch (Exception e) {
            logger.error("保存分片信息失败 => fileMd5: {}, chunkIndex: {}, 错误: {}",
                      fileMd5, chunkIndex, e.getMessage(), e);
            throw new RuntimeException("Failed to save chunk info", e);
        }
    }

    /**
     * 合并所有分片为完整文件
     */
    public String mergeChunks(String fileMd5, String fileName) {
        return mergeChunks(fileMd5, fileName, null);
    }

    /**
     * 合并所有分片为完整文件
     */
    public String mergeChunks(String fileMd5, String fileName, String userId) {
        String fileType = getFileType(fileName);
        logger.info("开始合并分片 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
        try {
            logger.debug("查询分片信息列表 => fileMd5: {}, fileName: {}", fileMd5, fileName);
            List<ChunkInfo> chunks = chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc(fileMd5);
            logger.info("查询到分片数量 => fileMd5: {}, fileName: {}, fileType: {}, 分片数: {}", fileMd5, fileName, fileType, chunks.size());
// PLACEHOLDER_MERGE_CHUNKS
            int expectedChunks = getTotalChunks(fileMd5);
            if (chunks.size() != expectedChunks) {
                logger.error("分片数量不匹配 => fileMd5: {}, fileName: {}, fileType: {}, 期望: {}, 实际: {}",
                          fileMd5, fileName, fileType, expectedChunks, chunks.size());
                throw new RuntimeException(String.format(
                    "分片数量不匹配，期望: %d, 实际: %d", expectedChunks, chunks.size()));
            }

            List<String> partPaths = chunks.stream()
                    .map(ChunkInfo::getStoragePath)
                    .collect(Collectors.toList());
            logger.debug("分片存储路径列表 => fileMd5: {}, fileName: {}, 分片数: {}", fileMd5, fileName, partPaths.size());
            logger.info("开始验证所有分片在MinIO中是否存在 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
            for (int i = 0; i < partPaths.size(); i++) {
                String path = partPaths.get(i);
                try {
                    StatObjectResponse stat = minioClient.statObject(
                        StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(path)
                            .build()
                    );
                    logger.debug("分片验证通过 => fileName: {}, index: {}, path: {}, size: {}", fileName, i, path, stat.size());
                } catch (Exception e) {
                    logger.error("分片在MinIO中不存在 => fileName: {}, index: {}, path: {}, 错误: {}",
                              fileName, i, path, e.getMessage(), e);
                    throw new RuntimeException("分片 " + i + " 在MinIO中不存在: " + e.getMessage(), e);
                }
            }
            logger.info("所有分片验证通过，开始执行合并 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);

            String mergedPath = "merged/" + fileName;
            logger.info("合并目标路径 => fileMd5: {}, fileName: {}, fileType: {}, mergedPath: {}", fileMd5, fileName, fileType, mergedPath);
// PLACEHOLDER_MERGE_EXECUTE

            try {
                List<ComposeSource> sources = partPaths.stream()
                        .map(path -> ComposeSource.builder().bucket(bucketName).object(path).build())
                        .collect(Collectors.toList());

                logger.debug("构建MinIO合并请求 => fileMd5: {}, fileName: {}, targetPath: {}, sourcePaths: {}",
                          fileMd5, fileName, mergedPath, partPaths);

                minioClient.composeObject(
                        ComposeObjectArgs.builder()
                                .bucket(bucketName)
                                .object(mergedPath)
                                .sources(sources)
                                .build()
                );
                logger.info("MinIO合并操作完成 => fileMd5: {}, fileName: {}, fileType: {}, mergedPath: {}", fileMd5, fileName, fileType, mergedPath);
                StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                        .bucket(bucketName)
                        .object(mergedPath)
                        .build()
                );
                logger.info("合并后文件信息 => fileMd5: {}, fileName: {}, fileType: {}, path: {}, size: {}", fileMd5, fileName, fileType, mergedPath, stat.size());
                logger.info("开始清理分片文件 => fileMd5: {}, fileName: {}, 分片数: {}", fileMd5, fileName, partPaths.size());
                for (String path : partPaths) {
                    try {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(path)
                                        .build()
                        );
                        logger.debug("分片文件已删除 => fileName: {}, path: {}", fileName, path);
                    } catch (Exception e) {
                        logger.warn("删除分片文件失败（非致命） => fileName: {}, path: {}, 错误: {}", fileName, path, e.getMessage());
                    }
                }
                logger.info("分片文件清理完成 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
// PLACEHOLDER_MERGE_CLEANUP
                logger.info("删除Redis上传标记 => fileMd5: {}, fileName: {}", fileMd5, fileName);
                deleteFileMark(fileMd5);
                logger.info("Redis上传标记已删除 => fileMd5: {}, fileName: {}", fileMd5, fileName);
                logger.info("更新文件上传状态为已完成 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
                Optional<FileUpload> fileUploadOptional = userId == null || userId.isBlank()
                        ? fileUploadRepository.findFirstByFileMd5OrderByIdAsc(fileMd5)
                        : fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId);
                FileUpload fileUpload = fileUploadOptional
                        .orElseThrow(() -> {
                            logger.error("更新状态时文件记录不存在 => fileMd5: {}, fileName: {}, userId: {}", fileMd5, fileName, userId);
                            return new RuntimeException("文件上传记录不存在: " + fileMd5);
                        });
                fileUpload.setStatus(1);
                fileUpload.setMergedAt(LocalDateTime.now());
                fileUploadRepository.save(fileUpload);
                logger.info("文件状态更新为已合并 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
                logger.info("生成预签名URL => fileMd5: {}, fileName: {}, path: {}", fileMd5, fileName, mergedPath);
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(mergedPath)
                                .expiry(1, TimeUnit.HOURS)
                                .build()
                );
                logger.info("预签名URL生成成功 => fileMd5: {}, fileName: {}, fileType: {}, URL: {}", fileMd5, fileName, fileType, presignedUrl);

                return presignedUrl;
            } catch (Exception e) {
                logger.error("合并文件失败 => fileMd5: {}, fileName: {}, fileType: {}, 异常类型: {}, 错误: {}",
                          fileMd5, fileName, fileType, e.getClass().getName(), e.getMessage(), e);
                throw new RuntimeException("合并文件失败: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            logger.error("文件合并流程异常 => fileMd5: {}, fileName: {}, fileType: {}, 异常类型: {}, 错误: {}",
                      fileMd5, fileName, fileType, e.getClass().getName(), e.getMessage(), e);
            throw new RuntimeException("文件合并流程异常: " + e.getMessage(), e);
        }
    }
// PLACEHOLDER_EXCEPTION_CLASS

    /**
     * 文件类型校验异常
     */
    public static class FileValidationException extends RuntimeException {
        private final String fileType;
        private final Set<String> supportedTypes;

        public FileValidationException(String message, String fileType, Set<String> supportedTypes) {
            super(message);
            this.fileType = fileType;
            this.supportedTypes = supportedTypes;
        }

        public String getFileType() {
            return fileType;
        }

        public Set<String> getSupportedTypes() {
            return supportedTypes;
        }
    }
}
