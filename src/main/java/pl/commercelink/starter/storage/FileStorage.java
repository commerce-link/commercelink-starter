package pl.commercelink.starter.storage;

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class FileStorage {

    private final S3Client s3Client;
    public static final String DATE_TIME_FORMAT = "dd MMM yyyy HH:mm:ss";

    public FileStorage(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void put(String bucketName, String key, byte[] data) {
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build(),
                RequestBody.fromBytes(data));
    }

    public InputStreamReader get(String bucketName, String key) {
        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
        return new InputStreamReader(response);
    }

    public byte[] getBytes(String bucketName, String key) {
        ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build(), ResponseTransformer.toBytes());
        return responseBytes.asByteArray();
    }

    public Pair<String,InputStreamReader> findNewestByLastModified(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);

        return newestByLastModified(listObjects)
                .map(s3Object -> {
                    ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Object.key())
                            .build());
                    String fileName = Paths.get(s3Object.key()).getFileName().toString();
                    return Pair.of(fileName, new InputStreamReader(response));
                })
                .orElse(null);
    }

    public Optional<String> findNewestFileNameByLastModified(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);
        return newestByLastModified(listObjects)
                .map(s3Object -> Paths.get(s3Object.key()).getFileName().toString());
    }

    public List<Pair<String, String>> findTopNByLastModified(String bucketName, String prefix, int n) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);

        return listObjects.contents().stream()
                .sorted(Comparator.comparing(S3Object::lastModified).reversed())
                .limit(n)
                .map(s3Object -> {
                    String fileName = Paths.get(s3Object.key()).getFileName().toString();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);
                    LocalDateTime dateTime = LocalDateTime.ofInstant(s3Object.lastModified(), ZoneId.systemDefault());
                    return Pair.of(fileName, dateTime.format(formatter));
                })
                .collect(Collectors.toList());
    }

    public byte[] findNewestAsBytesByLastModified(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);
        return newestByLastModified(listObjects)
                .map(s3Object -> {
                    ResponseBytes<?> responseBytes = s3Client.getObject(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Object.key())
                            .build(), ResponseTransformer.toBytes());
                    return responseBytes.asByteArray();
                }).orElse(new byte[0]);
    }

    public Pair<String, InputStreamReader> findNewestByKeyOrder(String bucketName, String prefix) {
        return firstKeyOf(bucketName, prefix)
                .map(key -> {
                    ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build());
                    return Pair.of(fileNameOf(key), new InputStreamReader(response));
                })
                .orElse(null);
    }

    public Optional<String> findNewestFileNameByKeyOrder(String bucketName, String prefix) {
        return firstKeyOf(bucketName, prefix).map(FileStorage::fileNameOf);
    }

    public byte[] findNewestAsBytesByKeyOrder(String bucketName, String prefix) {
        return firstKeyOf(bucketName, prefix)
                .map(key -> {
                    ResponseBytes<?> responseBytes = s3Client.getObject(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build(), ResponseTransformer.toBytes());
                    return responseBytes.asByteArray();
                })
                .orElse(new byte[0]);
    }

    public List<Pair<String, String>> findTopNByKeyOrder(String bucketName, String prefix, int n) {
        if (n <= 0) {
            return List.of();
        }
        return listObjects(bucketName, prefix, n).contents().stream()
                .map(s3Object -> {
                    String fileName = fileNameOf(s3Object.key());
                    return Pair.of(fileName, readableTimestampOf(fileName, s3Object.lastModified()));
                })
                .collect(Collectors.toList());
    }

    public List<String> findKeysByKeyOrder(String bucketName, String prefix, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return listObjects(bucketName, prefix, limit).contents().stream()
                .map(S3Object::key)
                .toList();
    }

    public List<String> findAllKeysByKeyOrder(String bucketName, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            response.contents().forEach(s3Object -> keys.add(s3Object.key()));
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
        return List.copyOf(keys);
    }

    private Optional<String> firstKeyOf(String bucketName, String prefix) {
        return listObjects(bucketName, prefix, 1).contents().stream()
                .findFirst()
                .map(S3Object::key);
    }

    private static String fileNameOf(String key) {
        return Paths.get(key).getFileName().toString();
    }

    private static String readableTimestampOf(String fileName, Instant lastModified) {
        Instant instant = TimeOrderedFileName.instantOf(fileName).orElse(lastModified);
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT));
    }

    public List<Reader> find(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);

        List<Reader> readers = new ArrayList<>();
        for (S3Object s3Object : listObjects.contents()) {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Object.key())
                    .build());
            readers.add(new InputStreamReader(response));
        }
        return readers;
    }

    public Optional<Instant> findLastModified(String bucketName, String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return Optional.of(response.lastModified());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    public boolean canRead(String bucketName, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private ListObjectsV2Response listObjects(String bucketName, String prefix) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build());
    }

    private ListObjectsV2Response listObjects(String bucketName, String prefix, int maxKeys) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .maxKeys(maxKeys)
                .build());
    }

    private Optional<S3Object> newestByLastModified(ListObjectsV2Response listObjects) {
        return listObjects.contents().stream().max(Comparator.comparing(S3Object::lastModified));
    }

    public void delete(String bucketName, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
    }

    public void deleteAll(String bucketName, String prefix) {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
        if (listResponse.contents().isEmpty()) {
            return;
        }

        List<ObjectIdentifier> keys = listResponse.contents().stream()
                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                .collect(Collectors.toList());
        DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(Delete.builder().objects(keys).build())
                .build();

        s3Client.deleteObjects(deleteRequest);
    }

    public Map<String, LocalDateTime> getAllObjectLastModified(String bucket, String prefix) {
        ListObjectsV2Response response = listObjects(bucket, prefix);

        Map<String, LocalDateTime> lastModifiedMap = new HashMap<>();

        for (S3Object object : response.contents()) {
            String key = object.key();
            LocalDateTime lastModified = object.lastModified().atZone(ZoneId.systemDefault()).toLocalDateTime();
            lastModifiedMap.put(key, lastModified);
        }

        return lastModifiedMap;
    }

}
