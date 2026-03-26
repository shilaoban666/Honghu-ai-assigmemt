@echo off
chcp 65001 >nul
setlocal

echo ========================================
echo 修复 Spring Data JPA 依赖
echo ========================================
echo.

echo 正在清理 Maven 缓存...
rmdir /s /q %USERPROFILE%\.m2\repository\org\springframework\data 2>nul
rmdir /s /q %USERPROFILE%\.m2\repository\org\springframework\boot\spring-boot-starter-data-jpa 2>nul
echo 清理完成！
echo.

echo 设置 JVM 参数...
set MAVEN_OPTS=-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8

echo 正在强制更新依赖（这可能需要几分钟）...
call mvn dependency:purge-local-repository -U -DmanualInclude="org.springframework.boot:spring-boot-starter-data-jpa,org.springframework.data:spring-data-jpa"
echo.

echo 正在重新编译项目...
call mvn clean compile -U -DskipTests
echo.

echo ========================================
echo 修复完成！
echo ========================================
pause
