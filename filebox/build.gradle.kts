plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

group = "com.example"
version = "1.0.0"

apiPlugin {
    pluginId = "filebox"
    pluginName = "文件盒插件"
    versionCode = 1
    description = "文件盒插件，演示插件独立数据源、文件存储（内容寻址 / 位置寻址）与免登外链"
    author = "FatttSnake"
    mainClass = "com.example.filebox.FileboxLifecycle"
}
