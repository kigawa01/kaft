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

    private fun dataKey(uuid: String) = "$uuid/data"
    private fun metaKey(uuid: String) = "$uuid/meta.json"

    override fun exists(uuid: String): Boolean = headExists(metaKey(uuid))

    override fun savePending(uuid: String, data: ByteArray) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(dataKey(uuid)).build(),
            RequestBody.fromBytes(data),
        )
        writeMeta(uuid, FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE))
    }

    override fun confirm(uuid: String) {
        val meta = getMeta(uuid) ?: error("File not found: $uuid")
        writeMeta(uuid, meta.copy(state = FileState.CONFIRMED))
    }

    override fun getBytes(uuid: String): ByteArray? {
        if (!headExists(dataKey(uuid))) return null
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(dataKey(uuid)).build())
            .use { it.readAllBytes() }
    }

    override fun getMeta(uuid: String): FileMeta? {
        if (!headExists(metaKey(uuid))) return null
        val json = client.getObject(GetObjectRequest.builder().bucket(bucket).key(metaKey(uuid)).build())
            .use { it.readAllBytes() }
        return Json.decodeFromString(String(json))
    }

    override fun delete(uuid: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(dataKey(uuid)).build())
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(metaKey(uuid)).build())
    }

    override fun updateVisibility(uuid: String, visibility: Visibility) {
        val meta = getMeta(uuid) ?: error("File not found: $uuid")
        writeMeta(uuid, meta.copy(visibility = visibility))
    }

    private fun writeMeta(uuid: String, meta: FileMeta) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(metaKey(uuid)).build(),
            RequestBody.fromString(Json.encodeToString(meta)),
        )
    }

    private fun headExists(key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }
}
