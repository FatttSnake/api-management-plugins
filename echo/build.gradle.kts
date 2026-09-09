plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

group = "com.example"
version = "1.0.0"

apiPlugin {
    pluginId = "echo"
    pluginName = "Echo 插件"
    versionCode = 1
    description = "Echo 插件，演示 API 插件热插拔全流程（安装/升级/卸载/禁用/签名/文档）"
    author = "FatttSnake"
    mainClass = "com.example.echo.EchoLifecycle"
}
