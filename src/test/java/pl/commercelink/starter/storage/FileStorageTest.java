package pl.commercelink.starter.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageTest {

    private static final String BUCKET = "stores";
    private static final String PREFIX = "store-1/marketplace-exports/";

    private final S3Client s3Client = mock(S3Client.class);
    private final FileStorage fileStorage = new FileStorage(s3Client);

    @Test
    void listsKeysInTheOrderTheListingReturnsThem() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(false, null, PREFIX + "a.csv", PREFIX + "b.csv", PREFIX + "c.csv"));

        assertEquals(List.of(PREFIX + "a.csv", PREFIX + "b.csv", PREFIX + "c.csv"),
                fileStorage.findKeysByKeyOrder(BUCKET, PREFIX, 3));
    }

    @Test
    void asksTheListingForNoMoreKeysThanTheLimit() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null, PREFIX + "a.csv"));

        fileStorage.findKeysByKeyOrder(BUCKET, PREFIX, 5);

        ListObjectsV2Request request = captureRequests().get(0);
        assertEquals(BUCKET, request.bucket());
        assertEquals(PREFIX, request.prefix());
        assertEquals(5, request.maxKeys());
    }

    @Test
    void returnsNoKeysWithoutListingWhenTheLimitIsNotPositive() {
        assertEquals(List.of(), fileStorage.findKeysByKeyOrder(BUCKET, PREFIX, 0));
        assertEquals(List.of(), fileStorage.findKeysByKeyOrder(BUCKET, PREFIX, -1));

        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void returnsAnEmptyListWhenThePrefixHoldsNoObjects() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null));

        assertEquals(List.of(), fileStorage.findKeysByKeyOrder(BUCKET, PREFIX, 10));
        assertEquals(List.of(), fileStorage.findAllKeysByKeyOrder(BUCKET, PREFIX));
    }

    @Test
    void followsTheContinuationTokenUntilTheListingIsComplete() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(true, "token-1", PREFIX + "a.csv", PREFIX + "b.csv"))
                .thenReturn(page(true, "token-2", PREFIX + "c.csv"))
                .thenReturn(page(false, null, PREFIX + "d.csv"));

        List<String> keys = fileStorage.findAllKeysByKeyOrder(BUCKET, PREFIX);

        assertEquals(List.of(PREFIX + "a.csv", PREFIX + "b.csv", PREFIX + "c.csv", PREFIX + "d.csv"), keys);

        List<ListObjectsV2Request> requests = captureRequests();
        assertEquals(3, requests.size());
        assertEquals(List.of("first-page", "token-1", "token-2"),
                requests.stream().map(request -> request.continuationToken() == null
                        ? "first-page"
                        : request.continuationToken()).toList());
    }

    @Test
    void stopsAfterTheFirstPageWhenTheListingIsNotTruncated() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(false, "token-1", PREFIX + "a.csv"));

        assertEquals(List.of(PREFIX + "a.csv"), fileStorage.findAllKeysByKeyOrder(BUCKET, PREFIX));
        assertEquals(1, captureRequests().size());
    }

    @Test
    void listsEveryPageWithoutABoundOnTheNumberOfKeys() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null, PREFIX + "a.csv"));

        fileStorage.findAllKeysByKeyOrder(BUCKET, PREFIX);

        ListObjectsV2Request request = captureRequests().get(0);
        assertEquals(BUCKET, request.bucket());
        assertEquals(PREFIX, request.prefix());
        assertNull(request.maxKeys());
    }

    private List<ListObjectsV2Request> captureRequests() {
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, atLeastOnce()).listObjectsV2(captor.capture());
        return captor.getAllValues();
    }

    private ListObjectsV2Response page(boolean truncated, String nextContinuationToken, String... keys) {
        return ListObjectsV2Response.builder()
                .contents(Arrays.stream(keys).map(key -> S3Object.builder().key(key).build()).toList())
                .isTruncated(truncated)
                .nextContinuationToken(nextContinuationToken)
                .build();
    }
}
