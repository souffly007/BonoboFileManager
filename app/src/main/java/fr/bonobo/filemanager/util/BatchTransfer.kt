package fr.bonobo.filemanager.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object BatchTransfer {
    suspend fun <T> run(items: List<T>, progress: (Int, T) -> Unit,
        transfer: suspend (T) -> Result<Unit>, completed: (T, Result<Unit>) -> Unit) {
        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            progress(index + 1, item)
            val result = try { transfer(item) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Result.failure(error)
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            completed(item, result)
        }
    }
}
