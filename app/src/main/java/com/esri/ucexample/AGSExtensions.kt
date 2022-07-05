package com.esri.ucexample

import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.loadable.Loadable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T : Loadable> T.load() =
    suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        val loadingListener = object : Runnable {
            override fun run() {
                removeDoneLoadingListener(this)

                if (cont.isCompleted) return
                when (loadStatus) {
                    LoadStatus.LOADED -> cont.resume(Unit)
                    LoadStatus.FAILED_TO_LOAD -> cont.resumeWithException(loadError)
                    else -> cont.resume(Unit)
                }
            }
        }

        addDoneLoadingListener(loadingListener)

        cont.invokeOnCancellation {
            removeDoneLoadingListener(loadingListener)
        }

        retryLoadAsync()
    }

suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
        val doneListener = object : Runnable {
            override fun run() {
                removeDoneListener(this)

                if (cont.isCompleted) return
                val result = try {
                    get()
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                    return
                }
                cont.resume(result as T)
            }
        }

        addDoneListener(doneListener)
        cont.invokeOnCancellation {
            removeDoneListener(doneListener)
            cancel(false)
        }
    }

sealed class ResultOf<out V : Any?, out E : Any?> {
    data class Success<out V : Any?>(val value: V) : ResultOf<V, Nothing>()
    data class Failure<out E : Any?>(val error: E) : ResultOf<Nothing, E>()
}

inline fun <T, E> ResultOf<T, E>.onSuccess(onSuccess: (value: T) -> Unit): ResultOf<T, E> {
    when (this) {
        is ResultOf.Success -> onSuccess(value)
        else -> Unit
    }
    return this
}

inline fun <T, E> ResultOf<T, E>.onFailure(onFailure: (error: E) -> Unit): ResultOf<T, E> {
    when (this) {
        is ResultOf.Failure -> onFailure(error)
        else -> Unit
    }
    return this
}
