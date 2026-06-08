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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        List<FileUpload> uploads = fileUploadRepository.findAllByFileMd5(fileMd5);
        if (uploads.isEmpty()) {
            throw new IllegalArgumentException("DOCUMENT_NOT_FOUND");
        }

        boolean isAdmin = "ADMIN".equalsIgnoreCase(requesterRole);
        if (isAdmin) {
            deleteAllCopies(fileMd5, uploads);
            return;
        }

        if (requesterUserId == null || requesterUserId.isBlank()) {
            throw new SecurityException("PERMISSION_DENIED");
        }

        List<FileUpload> requesterUploads = uploads.stream()
                .filter(upload -> requesterUserId.equals(upload.getUserId()))
                .toList();
        if (requesterUploads.isEmpty()) {
            throw new SecurityException("PERMISSION_DENIED");
        }

        deleteUserCopies(fileMd5, requesterUserId, requesterUploads);
    }

    /**
     * Internal delete operation.
     */
    @Transactional
    public void deleteDocument(String fileMd5) {
        logger.info("Start deleting document: {}", fileMd5);

        try {
            List<FileUpload> uploads = fileUploadRepository.findAllByFileMd5(fileMd5);
            if (uploads.isEmpty()) {
                throw new RuntimeException("Document not found");
            }
            deleteAllCopies(fileMd5, uploads);
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
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByIdAsc(fileMd5)
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

    private void deleteAllCopies(String fileMd5, List<FileUpload> uploads) {
        logger.info("Delete all copies for fileMd5={}, copyCount={}", fileMd5, uploads.size());
        cleanupAllSearchResources(fileMd5);
        removeMinioObjects(fileMd5, uploads);
        fileUploadRepository.deleteByFileMd5(fileMd5);
        logger.info("Deleted all file upload records for fileMd5={}", fileMd5);
    }

    private void deleteUserCopies(String fileMd5, String userId, List<FileUpload> uploadsToDelete) {
        logger.info("Delete user copies for fileMd5={}, userId={}, copyCount={}", fileMd5, userId, uploadsToDelete.size());
        cleanupUserSearchResources(fileMd5, userId);
        fileUploadRepository.deleteByFileMd5AndUserId(fileMd5, userId);

        long remainingCopies = fileUploadRepository.countByFileMd5(fileMd5);
        if (remainingCopies == 0) {
            cleanupAllSearchResources(fileMd5);
            removeMinioObjects(fileMd5, uploadsToDelete);
            logger.info("No remaining file upload records, shared resources cleaned for fileMd5={}", fileMd5);
        } else {
            logger.info("Keep shared MinIO object for fileMd5={}, remainingCopies={}", fileMd5, remainingCopies);
        }
    }

    private void cleanupAllSearchResources(String fileMd5) {
        try {
            elasticsearchService.deleteByFileMd5(fileMd5);
            logger.info("Deleted Elasticsearch records for fileMd5={}", fileMd5);
        } catch (Exception e) {
            logger.error("Delete Elasticsearch records failed for fileMd5={}", fileMd5, e);
        }

        try {
            documentVectorRepository.deleteByFileMd5(fileMd5);
            logger.info("Deleted document vectors for fileMd5={}", fileMd5);
        } catch (Exception e) {
            logger.error("Delete document vectors failed for fileMd5={}", fileMd5, e);
        }
    }

    private void cleanupUserSearchResources(String fileMd5, String userId) {
        try {
            elasticsearchService.deleteByFileMd5AndUserId(fileMd5, userId);
            logger.info("Deleted Elasticsearch records for fileMd5={}, userId={}", fileMd5, userId);
        } catch (Exception e) {
            logger.error("Delete Elasticsearch records failed for fileMd5={}, userId={}", fileMd5, userId, e);
        }

        try {
            documentVectorRepository.deleteByFileMd5AndUserId(fileMd5, userId);
            logger.info("Deleted document vectors for fileMd5={}, userId={}", fileMd5, userId);
        } catch (Exception e) {
            logger.error("Delete document vectors failed for fileMd5={}, userId={}", fileMd5, userId, e);
        }
    }

    private void removeMinioObjects(String fileMd5, List<FileUpload> uploads) {
        Set<String> objectNames = new LinkedHashSet<>();
        for (FileUpload upload : uploads) {
            if (upload.getFileName() != null && !upload.getFileName().isBlank()) {
                objectNames.add("merged/" + upload.getFileName());
            }
        }

        for (String objectName : objectNames) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
                logger.info("Deleted MinIO object: {}", objectName);
            } catch (Exception e) {
                logger.error("Delete MinIO object failed for fileMd5={}, objectName={}", fileMd5, objectName, e);
            }
        }
    }
}
