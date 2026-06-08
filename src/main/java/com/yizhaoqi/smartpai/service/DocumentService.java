package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Document management service.
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * Permission-checked delete entrypoint.
     */
    @Transactional
    public void deleteDocument(String fileMd5, String requesterUserId, String requesterRole) {
        FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                .orElseThrow(() -> new IllegalArgumentException("DOCUMENT_NOT_FOUND"));

        boolean isOwner = requesterUserId != null && requesterUserId.equals(fileUpload.getUserId());
        boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);
        if (!isOwner && !isAdmin) {
            throw new SecurityException("PERMISSION_DENIED");
        }

        deleteDocument(fileMd5);
    }

    /**
     * Internal delete operation.
     */
    @Transactional
    public void deleteDocument(String fileMd5) {
        logger.info("Start deleting document: {}", fileMd5);

        try {
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("Deleted Elasticsearch records for fileMd5={}", fileMd5);
            } catch (Exception e) {
                logger.error("Delete Elasticsearch records failed for fileMd5={}", fileMd5, e);
            }

            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
                logger.info("Deleted MinIO object: {}", objectName);
            } catch (Exception e) {
                logger.error("Delete MinIO object failed for fileMd5={}", fileMd5, e);
            }

            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("Deleted document vectors for fileMd5={}", fileMd5);
            } catch (Exception e) {
                logger.error("Delete document vectors failed for fileMd5={}", fileMd5, e);
            }

            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("Deleted file upload record for fileMd5={}", fileMd5);
        } catch (Exception e) {
            logger.error("Delete document failed, fileMd5={}", fileMd5, e);
            throw new RuntimeException("Delete document failed: " + e.getMessage(), e);
        }
    }

    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("Get accessible files for userId={}", userId);

        try {
            User user = userRepository.findById(Long.parseLong(userId))
                    .orElseThrow(() -> new RuntimeException("User not found: " + userId));

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            if (userEffectiveTags.isEmpty()) {
                return fileUploadRepository.findByUserIdOrIsPublicTrue(userId);
            }
            return fileUploadRepository.findAccessibleFilesWithTags(userId, userEffectiveTags);
        } catch (Exception e) {
            logger.error("Get accessible files failed, userId={}", userId, e);
            throw new RuntimeException("Get accessible files failed: " + e.getMessage(), e);
        }
    }

    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("Get uploaded files for userId={}", userId);

        try {
            return fileUploadRepository.findByUserId(userId);
        } catch (Exception e) {
            logger.error("Get uploaded files failed, userId={}", userId, e);
            throw new RuntimeException("Get uploaded files failed: " + e.getMessage(), e);
        }
    }

    public String generateDownloadUrl(String fileMd5) {
        logger.info("Generate download url, fileMd5={}", fileMd5);

        try {
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("Document not found: " + fileMd5));

            String objectName = "merged/" + fileUpload.getFileName();
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(3600)
                    .build());
        } catch (Exception e) {
            logger.error("Generate download url failed, fileMd5={}", fileMd5, e);
            return null;
        }
    }
}
