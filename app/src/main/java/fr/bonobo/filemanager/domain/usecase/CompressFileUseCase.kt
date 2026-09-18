package fr.bonobo.filemanager.domain.usecase

import java.io.File
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import javax.inject.Inject

class CompressFileUseCase @Inject constructor() {

    suspend operator fun invoke(
        sources: List<String>,
        outputZip: String,
        password: String? = null
    ): Result<Unit> {
        return runCatching {
            val zip = ZipFile(outputZip, password?.toCharArray())
            val parameters = ZipParameters().apply {
                if (!password.isNullOrBlank()) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                }
            }
            sources.forEach { sourcePath ->
                val source = File(sourcePath)
                if (source.isDirectory) zip.addFolder(source, parameters) else zip.addFile(source, parameters)
            }
        }
    }
}
