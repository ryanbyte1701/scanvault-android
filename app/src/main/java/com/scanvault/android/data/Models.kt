package com.scanvault.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** Proof this app signed in to the account before; lets the server skip its account lockout. */
    val device: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** Whether the vault is unlocked; SSO never unlocks it, so false means ask for the passphrase. */
    val unlocked: Boolean? = null,
    /** Device token to store and send back on the next password sign-in (non-browser clients). */
    val device: String? = null,
)

/** An identity provider the server offers for single sign-on. */
@Serializable
data class SsoProvider(val id: String, val name: String, val preset: String? = null)

@Serializable
data class SsoExchangeRequest(
    val ticket: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
data class ServerStatus(
    @SerialName("setup_complete") val setupComplete: Boolean = true,
    val unlocked: Boolean = true,
    val authenticated: Boolean = false,
    val username: String? = null,
    @SerialName("vault_id") val vaultId: String? = null,
    val fingerprint: String? = null,
    val version: String? = null,
    /** False when the server only allows SSO, so the password form is hidden. */
    @SerialName("local_login") val localLogin: Boolean = true,
    val sso: List<SsoProvider> = emptyList(),
)

@Serializable
data class UnlockRequest(
    val passphrase: String? = null,
    @SerialName("recovery_key") val recoveryKey: String? = null,
)

@Serializable
data class Patient(
    val id: String,
    val name: String,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val sex: String? = null,
    val mrn: String? = null,
    val notes: String? = null,
    @SerialName("study_count") val studyCount: Int = 0,
)

@Serializable
data class PatientInput(
    val name: String,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val sex: String? = null,
    val mrn: String? = null,
    val notes: String? = null,
)

@Serializable
data class Study(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    val title: String,
    val modality: String? = null,
    @SerialName("study_date") val studyDate: String? = null,
    val facility: String? = null,
    val description: String? = null,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Another study of the same patient, scored for how well it compares with the one being viewed. */
@Serializable
data class SimilarStudy(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    val title: String,
    val modality: String? = null,
    @SerialName("study_date") val studyDate: String? = null,
    val facility: String? = null,
    @SerialName("body_part") val bodyPart: String? = null,
    val similarity: Double = 0.0,
    /** Same kind of exam: the server's pick for a side-by-side comparison. */
    val suggested: Boolean = false,
    @SerialName("match_reasons") val matchReasons: List<String> = emptyList(),
    @SerialName("days_apart") val daysApart: Int? = null,
    /** "prior", "later" or "same day", relative to the study being viewed. */
    val relation: String? = null,
    @SerialName("thumbnail_ids") val thumbnailIds: List<String> = emptyList(),
    @SerialName("image_count") val imageCount: Int = 0,
)

@Serializable
data class StudyInput(
    val title: String,
    val modality: String? = null,
    @SerialName("study_date") val studyDate: String? = null,
    val facility: String? = null,
    val description: String? = null,
)

@Serializable
data class VaultFile(
    val id: String,
    @SerialName("study_id") val studyId: String,
    /** The server can return null for a blank name; default it so one bad file can't break a study. */
    val filename: String = "file",
    @SerialName("content_type") val contentType: String = "application/octet-stream",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val kind: String = KIND_OTHER,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
    val dicom: JsonObject? = null,
    /** True when the server already had these exact bytes in the study and skipped storing a copy. */
    val duplicate: Boolean = false,
) {
    val isImage: Boolean get() = kind == KIND_IMAGE || contentType.startsWith("image/")

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_REPORT = "report"
        const val KIND_DICOM = "dicom"
        const val KIND_OTHER = "other"
    }
}

@Serializable
data class ImportResult(
    val studies: List<Study> = emptyList(),
    val files: List<VaultFile> = emptyList(),
    val skipped: List<kotlinx.serialization.json.JsonElement> = emptyList(),
)

@Serializable
data class ErrorBody(val error: String? = null, val code: String? = null)

/** A server-rendered DICOM frame plus the window/level and value range the server used. */
class RenderedImage(
    val bytes: ByteArray,
    val center: Double,
    val width: Double,
    val defaultCenter: Double?,
    val defaultWidth: Double?,
    val min: Double?,
    val max: Double?,
    val frames: Int,
    val frame: Int,
    val rgb: Boolean,
    val modality: String?,
)
