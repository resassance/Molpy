package app.musicplayer.ui

import app.musicplayer.model.*

enum class ErrorAction { RETRY, NEXT, EDIT_NETWORK, MANAGE_STORAGE, NONE }
data class ErrorPresentation(val message: String, val primaryAction: ErrorAction)

/** Only normalized categories enter user copy. Operation IDs and provider data stay out of the UI. */
fun present(error: AppError): ErrorPresentation {
    val (message, action) = when (error.code) {
        ErrorCode.NETWORK -> "Нет соединения или сервер не отвечает. Проверьте сеть и прокси." to ErrorAction.RETRY
        ErrorCode.PROXY_AUTH -> "Прокси отклонил авторизацию. Проверьте имя пользователя и пароль." to ErrorAction.EDIT_NETWORK
        ErrorCode.PROXY_UNSUPPORTED -> "Этот режим прокси не поддерживается. Выберите HTTP CONNECT или SOCKS5 без авторизации." to ErrorAction.EDIT_NETWORK
        ErrorCode.INVALID_CONFIG -> "Проверьте настройки сетевого подключения." to ErrorAction.EDIT_NETWORK
        ErrorCode.SOURCE_UNAVAILABLE -> "Эта запись больше недоступна у источника." to ErrorAction.NEXT
        ErrorCode.REGION_RESTRICTED -> "Эта запись недоступна в вашем регионе." to ErrorAction.NEXT
        ErrorCode.LOGIN_REQUIRED -> "Для этой записи источник требует входа в аккаунт." to ErrorAction.NEXT
        ErrorCode.RATE_LIMIT -> "Источник ограничил запросы. Повторите позже." to ErrorAction.RETRY
        ErrorCode.PROVIDER_CHANGED -> "Источник изменил формат ответа. Может потребоваться обновление приложения." to ErrorAction.NONE
        ErrorCode.UNSUPPORTED_TRANSPORT -> "Источник использует неподдерживаемый способ воспроизведения." to ErrorAction.NEXT
        ErrorCode.URL_REJECTED -> "Ссылка на аудио устарела или отклонена. Запросите источник снова." to ErrorAction.RETRY
        ErrorCode.STORAGE_FULL -> "Недостаточно места на устройстве. Освободите место в хранилище." to ErrorAction.MANAGE_STORAGE
        ErrorCode.INCOMPLETE_MEDIA -> "Аудиозапись получена не полностью. Повторите загрузку источника." to ErrorAction.RETRY
        ErrorCode.INCOMPATIBLE_RESOURCE -> "Эта аудиозапись несовместима с проигрывателем." to ErrorAction.NEXT
        ErrorCode.DATABASE -> "Не удалось прочитать или сохранить локальные данные." to if (error.retryable) ErrorAction.RETRY else ErrorAction.NONE
        ErrorCode.UNKNOWN -> "Не удалось выполнить действие." to if (error.retryable) ErrorAction.RETRY else ErrorAction.NONE
    }
    return ErrorPresentation(message, action)
}

internal fun performErrorAction(action: ErrorAction, retry: () -> Unit, next: () -> Unit,
    editNetwork: () -> Unit, manageStorage: () -> Unit) {
    when (action) {
        ErrorAction.RETRY -> retry()
        ErrorAction.NEXT -> next()
        ErrorAction.EDIT_NETWORK -> editNetwork()
        ErrorAction.MANAGE_STORAGE -> manageStorage()
        ErrorAction.NONE -> Unit
    }
}
