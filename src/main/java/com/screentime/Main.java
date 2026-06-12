/* ============================================================
 *  Main.java — jpackage 入口包装器
 *  避免 jpackage 的 JavaFX 模块查找问题：
 *  jpackage 在 --add-modules 模式下会使用 JavaFX 启动器
 *  查找 Application 子类，但非模块化 JAR 会在模块路径上
 *  找不到该类。此包装器不继承 Application，绕过该限制。
 * ============================================================ */
package com.screentime;

/**
 * jpackage 启动入口。不继承 Application，仅代理到真正的 App。
 */
public class Main {
    public static void main(String[] args) {
        App.main(args);
    }
}
