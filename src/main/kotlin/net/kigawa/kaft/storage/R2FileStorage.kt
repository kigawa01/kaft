package net.kigawa.kaft.storage

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kigawa.kaft.config.R2StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

class R2FileStorage(config: R2StorageConfig) : FileStorage {

    private val bucket = config.bucket

    private val client: S3Client = S3Client.builder()
        .region(Region.of("auto"))
        .endpointOverride(URI.create("https://${config.accountId}.r2.cloudflarestorage.com"))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
            ),
        )
        .build()

    private fun dataKey(id: FileId) = "$id/data"
    private fun metaKey(id: FileId) = "$id/meta.json"

    override fun exists(id: FileId): Boolean = headExists(metaKey(id))

    override suspend fun createPending(id: FileId, data: ByteReadChannel, size: Long, contentType: String): CreateResult {
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(metaKey(id)).ifNoneMatch("*").build(),
                RequestBody.fromString(
                    Json.encodeToString(
                        FileMeta(
                            state = FileState.PENDING,
                            visibility = Visibility.PRIVATE,
                            contentType = contentType,
                            size = size,
                        ),
                    ),
                ),
            )
        } catch (e: S3Exception) {
            if (e.statusCode() == 412) return CreateResult.AlreadyExists else throw e
        }
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(dataKey(id)).build(),
            RequestBody.fromInputStream(data.toInputStream(), size),
        )
        return CreateResult.Created
    }

    override fun confirm(id: FileId) = updateMetaWithRetry(id) { it.copy(state = FileState.CONFIRMED) }

    override fun openReadChannel(id: FileId): ByteReadChannel? {
        if (!headExists(dataKey(id))) return null
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(dataKey(id)).build()).toByteReadChannel()
    }

    override fun getMeta(id: FileId): FileMeta? = getMetaWithETag(id)?.first

    override fun delete(id: FileId) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(dataKey(id)).build())
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(metaKey(id)).build())
    }

    override fun updateVisibility(id: FileId, visibility: Visibility) =
        updateMetaWithRetry(id) { it.copy(visibility = visibility) }

    private fun getMetaWithETag(id: FileId): Pair<FileMeta, String>? {
        if (!headExists(metaKey(id))) return null
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(metaKey(id)).build()).use {
            Json.decodeFromString<FileMeta>(String(it.readAllBytes())) to it.response().eTag()
        }
    }

    private fun updateMetaWithRetry(id: FileId, retries: Int = 5, transform: (FileMeta) -> FileMeta) {
        repeat(retries) {
            val (meta, etag) = getMetaWithETag(id) ?: error("File not found: $id")
            try {
                client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(metaKey(id)).ifMatch(etag).build(),
                    RequestBody.fromString(Json.encodeToString(transform(meta))),
                )
                return
            } catch (e: S3Exception) {
                if (e.statusCode() != 412) throw e
                // ETag不一致(競合) -> 再読み込みして再試行
            }
        }
        error("Failed to update metadata for $id: too many concurrent conflicts")
    }

    private fun headExists(key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        // HeadObjectの404は、レスポンスボディがないためNoSuchKeyExceptionに
        // マッピングされず、汎用のS3Exceptionとして返ってくる場合がある
        // （R2含む一部のS3互換実装で見られる挙動）。
        if (e.statusCode() == 404) false else throw e
    }
}
