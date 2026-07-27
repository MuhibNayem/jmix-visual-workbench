package com.jmixstudio.model

data class ProjectConfig(
    val projectRoot: String,
    val basePackage: String,
    val sourceRoot: String = "src/main/java",
    val resourceRoot: String = "src/main/resources",
    val jmixVersion: String = "2.4.0",
    val databaseType: DatabaseType = DatabaseType.POSTGRES
) {
    val sourcePath: String get() = "$projectRoot/$sourceRoot"
    val resourcePath: String get() = "$projectRoot/$resourceRoot"

    fun packageToPath(pkg: String): String =
        pkg.replace('.', '/')

    fun entitySourcePath(pkg: String): String =
        "$sourcePath/${packageToPath(pkg)}/entity"

    fun viewSourcePath(pkg: String): String =
        "$sourcePath/${packageToPath(pkg)}/view"

    fun viewXmlPath(pkg: String): String =
        "$resourcePath/${packageToPath(pkg)}/view"

    fun menuXmlPath(pkg: String): String =
        "$resourcePath/${packageToPath(pkg)}/menu.xml"

    fun messagesPath(pkg: String): String =
        "$resourcePath/${packageToPath(pkg)}/messages.properties"

    fun changelogPath(): String =
        "$resourceRoot/db/changelog"
}

enum class DatabaseType {
    POSTGRES, MYSQL, MSSQL, ORACLE, HSQLDB
}
