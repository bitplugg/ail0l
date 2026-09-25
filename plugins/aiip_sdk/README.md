# AIIA .aiip SDK

SDK для плагинов AIIA. Пакет собирается в `.aiip` и содержит
`manifest.json`, `plugin.dex` и `assets/`.

## Сборка

```bash
./gradlew packageAiip
```

Результат: `build/aiip/example.aiip`.

Task извлекает `classes.jar` из AAR, конвертирует его через Android D8 и
складывает готовый DEX в архив.

## Версии API

Манифест поддерживает:

```json
{
  "apiVersion": 1,
  "schemaVersion": 1,
  "minApiVersion": 1,
  "maxApiVersion": 1
}
```

- `apiVersion` — версия runtime API AIIA;
- `schemaVersion` — версия структуры manifest;
- `minApiVersion` и `maxApiVersion` — диапазон совместимых runtime;
- старые manifest автоматически мигрируют `mainClass` в `entryClass`.

Перед установкой AIIA проверяет диапазон совместимости и показывает
permissions пользователю.

## Минимальный plugin

```kotlin
class ExamplePlugin : com.aiia.plugin.sdk.AiiaPlugin {
    override fun name() = "example"
    override fun tools() = emptyList<com.aiia.plugin.sdk.ToolDefinition>()
    override suspend fun call(name: String, arguments: kotlinx.serialization.json.JsonObject) = "ok"
}
```

Подробный шаблон: https://github.com/bitplugg/aiia-aiip-template
