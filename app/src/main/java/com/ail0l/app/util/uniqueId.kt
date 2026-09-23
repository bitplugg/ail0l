package com.ail0l.app.util

import java.util.UUID

/** Уникальный идентификатор (для device id / idempotency) */
fun uniqueId(): String = UUID.randomUUID().toString()