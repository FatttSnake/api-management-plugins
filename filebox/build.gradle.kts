plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

group = "com.example"
version = "1.0.0"

dependencies {
    // Locked and shipped as plugin modules; the gateway only provides gateway-api.
    implementation("com.baomidou:mybatis-plus-core:3.5.17")
    implementation("com.baomidou:mybatis-plus-extension:3.5.17")
    implementation("com.baomidou:mybatis-plus-jsqlparser:3.5.17")
    implementation("org.flywaydb:flyway-core:13.3.0")
    implementation("org.flywaydb:flyway-mysql:13.3.0")
    // MyBatis-Plus initializes a Lombok logger in MybatisUtils; the plugin loader does not
    // expose the gateway's SLF4J, and the module's own dependency on it is optional
    implementation("org.slf4j:slf4j-api:2.0.18")
}

apiPlugin {
    pluginId = "filebox"
    pluginName = "文件盒插件"
    versionCode = 1
    description = "文件盒插件，演示插件独立数据源、文件存储（内容寻址 / 位置寻址）与免登外链"
    author = "FatttSnake"
    mainClass = "com.example.filebox.FileboxPlugin"
    requiresGateway = ">=1.0.0 <2.0.0"
    requiresGatewayApi = ">=1.0.0-SNAPSHOT <2.0.0"

    // Flyway resolves database types through a static ServiceLoader registry bound to the
    // loader that defined flyway-core; the MySQL support lives in flyway-mysql.
    sharedLoader("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql")
    // MyBatis-Plus references MyBatis and SLF4J from optional POM dependencies.
    sharedLoader(
        "com.baomidou:mybatis-plus-annotation",
        "com.baomidou:mybatis-plus-core",
        "com.baomidou:mybatis-plus-extension",
        "com.baomidou:mybatis-plus-jsqlparser",
        "com.baomidou:mybatis-plus-jsqlparser-common",
        "org.mybatis:mybatis",
        "org.slf4j:slf4j-api",
    )
}
