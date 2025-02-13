// 文件路径: build.gradle.kts (根目录)

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

// 这个 clean 任务定义只应该在根项目中出现一次
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}