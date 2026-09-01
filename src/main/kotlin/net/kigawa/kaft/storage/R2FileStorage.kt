package net.kigawa.kaft.storage

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

    override fun createPending(id: FileId, data: ByteArray): CreateResult {
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(metaKey(id)).ifNoneMatch("*").build(),
                RequestBody.fromString(
                    Json.encodeToString(FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE)),
                ),
            )
        } catch (e: S3Exception) {
            if (e.statusCode() == 412) return CreateResult.AlreadyExists else throw e
        }
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(dataKey(id)).build(),
            RequestBody.fromBytes(data),
        )
        return CreateResult.Created
    }

    override fun confirm(id: FileId) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(state = FileState.CONFIRMED))
    }

    override fun getBytes(id: FileId): ByteArray? {
        if (!headExists(dataKey(id))) return null
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(dataKey(id)).build())
            .use { it.readAllBytes() }
    }

    override fun getMeta(id: FileId): FileMeta? {
        if (!headExists(metaKey(id))) return null
        val json = client.getObject(GetObjectRequest.builder().bucket(bucket).key(metaKey(id)).build())
            .use { it.readAllBytes() }
        return Json.decodeFromString(String(json))
    }

    override fun delete(id: FileId) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(dataKey(id)).build())
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(metaKey(id)).build())
    }

    override fun updateVisibility(id: FileId, visibility: Visibility) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(visibility = visibility))
    }

    private fun writeMeta(id: FileId, meta: FileMeta) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(metaKey(id)).build(),
            RequestBody.fromString(Json.encodeToString(meta)),
        )
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
