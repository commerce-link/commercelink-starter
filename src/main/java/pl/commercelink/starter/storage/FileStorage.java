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

    public Pair<String,InputStreamReader> findNewest(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);

        return findNewest(listObjects)
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

    public Optional<String> findNewestFileName(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);
        return findNewest(listObjects)
                .map(s3Object -> Paths.get(s3Object.key()).getFileName().toString());
    }

    public List<Pair<String, String>> findTopN(String bucketName, String prefix,int n) {
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

    public byte[] findNewestAsBytes(String bucketName, String prefix) {
        ListObjectsV2Response listObjects = listObjects(bucketName, prefix);
        return findNewest(listObjects)
                .map(s3Object -> {
                    ResponseBytes<?> responseBytes = s3Client.getObject(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Object.key())
                            .build(), ResponseTransformer.toBytes());
                    return responseBytes.asByteArray();
                }).orElse(new byte[0]);
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

    private Optional<S3Object> findNewest(ListObjectsV2Response listObjects) {
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
        Map<String, LocalDateTime> lastModifiedMap = new HashMap<>();

        for (S3Object object : listAllObjects(bucket, prefix)) {
            String key = object.key();
            LocalDateTime lastModified = object.lastModified().atZone(ZoneId.systemDefault()).toLocalDateTime();
            lastModifiedMap.put(key, lastModified);
        }

        return lastModifiedMap;
    }

    private List<S3Object> listAllObjects(String bucket, String prefix) {
        List<S3Object> objects = new ArrayList<>();
        String continuationToken = null;

        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            objects.addAll(response.contents());
            continuationToken = response.nextContinuationToken();
        } while (continuationToken != null);

        return objects;
    }

}
