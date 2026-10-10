#!/usr/bin/env python3
"""校验两个加载器薄壳产出的 jar 内容。

编译通过不等于产物正确。本项目历史上踩过的坑都只在运行期才暴露：
mixin 注册路径写错、refmap 偶发丢失、assets 只从 neoforge 单侧拷贝导致静默漂移。
本脚本把「产物应该长什么样」固化成几条断言，让这类问题在 CI 阶段就失败。

只依赖 Python 标准库（ubuntu-latest 自带 python3）。
"""

from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
errors: list[str] = []

# Windows 下 stdout 默认走控制台代码页（GBK），直接输出中文会乱码；CI 是 UTF-8，不受影响
for stream in (sys.stdout, sys.stderr):
    stream.reconfigure(encoding="utf-8", errors="replace")


def fail(msg: str) -> None:
    errors.append(msg)


def read_props() -> dict[str, str]:
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    return dict(re.findall(r"^([A-Za-z0-9_.]+)=(.*)$", text, re.M))


def locate_jar(module: str, name: str) -> Path | None:
    libs = ROOT / module / "build" / "libs"
    if not libs.is_dir():
        fail(f"{module}/build/libs 不存在，构建可能没有产出 jar")
        return None
    matches = sorted(libs.glob("*.jar"))
    if len(matches) != 1:
        fail(f"{module}/build/libs 期望恰好 1 个 jar，实际 {[p.name for p in matches]}")
        return None
    if matches[0].name != name:
        fail(f"{module} 产物名不符：期望 {name}，实际 {matches[0].name}")
    return matches[0]


def jar_names(jar: zipfile.ZipFile) -> set[str]:
    return {i.filename for i in jar.infolist() if not i.is_dir()}


def check_mixins_config(jar: zipfile.ZipFile, names: set[str], mod_id: str, loader: str) -> None:
    """mixins.json 里声明的每个混入类都必须真的在 jar 里，且不能同时出现在两个段。"""
    entry = f"{mod_id}.mixins.json"
    if entry not in names:
        fail(f"[{loader}] jar 内缺少 {entry}")
        return
    try:
        cfg = json.loads(jar.read(entry))
    except json.JSONDecodeError as exc:
        fail(f"[{loader}] {entry} 不是合法 JSON：{exc}")
        return

    package = cfg.get("package", "").replace(".", "/")
    seen: dict[str, str] = {}
    for section in ("mixins", "client", "server"):
        for cls in cfg.get(section, []):
            if cls in seen:
                fail(f"[{loader}] 混入类 {cls} 同时出现在 {seen[cls]} 与 {section} 段")
            seen[cls] = section
            path = f"{package}/{cls.replace('.', '/')}.class"
            if path not in names:
                fail(f"[{loader}] {entry} 的 {section} 段声明了 {cls}，但 jar 内没有 {path}")

    if not seen:
        fail(f"[{loader}] {entry} 没有声明任何混入类")

    # Forge 1.20.1 运行时是 SRG 名，必须带 refmap；NeoForge 1.21.1 已是 Mojang 映射，不需要
    has_refmap_key = "refmap" in cfg
    if loader == "forge" and not has_refmap_key:
        fail(f"[{loader}] {entry} 缺少 refmap 字段（Forge 生产环境为 SRG 名，必须重映射）")
    if loader == "neoforge" and has_refmap_key:
        fail(f"[{loader}] {entry} 不应声明 refmap（1.21.1 已是 Mojang 映射）")


def check_mods_toml(jar: zipfile.ZipFile, names: set[str], mod_id: str,
                    version: str, loader: str, entry: str) -> None:
    if entry not in names:
        fail(f"[{loader}] jar 内缺少 {entry}")
        return
    text = jar.read(entry).decode("utf-8")
    leftovers = re.findall(r"\$\{[^}]*\}", text)
    if leftovers:
        fail(f"[{loader}] {entry} 存在未替换的占位符：{sorted(set(leftovers))}")
    if f'modId = "{mod_id}"' not in text:
        fail(f"[{loader}] {entry} 缺少 modId = \"{mod_id}\"")
    if f'version = "{version}"' not in text:
        fail(f"[{loader}] {entry} 的 version 未展开为 {version}")


def main() -> int:
    props = read_props()
    mod_id = props["mod_id"]
    version = props["mod_version"].strip()
    archive = mod_id.replace("_", "-")

    targets = [
        ("neoforge", f"{archive}-{version}-neoforge-{props['minecraft_version']}",
         "META-INF/neoforge.mods.toml"),
        ("forge", f"{archive}-{version}-forge-{props['forge_minecraft_version']}",
         "META-INF/mods.toml"),
    ]

    jars: dict[str, zipfile.ZipFile] = {}
    for module, name, toml_entry in targets:
        path = locate_jar(module, f"{name}.jar")
        if path is None:
            continue
        jar = zipfile.ZipFile(path)
        jars[module] = jar
        names = jar_names(jar)

        check_mixins_config(jar, names, mod_id, module)
        check_mods_toml(jar, names, mod_id, version, module, toml_entry)

        if not any(n.startswith("com/xm2nd/") for n in names):
            fail(f"[{module}] jar 内没有任何 com/xm2nd 类")

        # 跨壳串味：各自只能带自己的 mod 描述文件
        foreign = "META-INF/neoforge.mods.toml" if module == "forge" else "META-INF/mods.toml"
        if foreign in names:
            fail(f"[{module}] jar 内混入了另一侧的 {foreign}")

    if "forge" in jars:
        names = jar_names(jars["forge"])
        manifest = jars["forge"].read("META-INF/MANIFEST.MF").decode("utf-8")
        # forge/build.gradle 通过 manifest 声明 mixin 配置（mods.toml 没有 [[mixins]] 段）
        if f"MixinConfigs: {mod_id}.mixins.json" not in manifest:
            fail("[forge] MANIFEST.MF 缺少 MixinConfigs 声明，生产环境不会加载 mixin")
        # 全部混入均为 remap=false，refmap 的 mappings 为空属正常，只断言文件存在且是合法 JSON
        refmap = f"{mod_id}.refmap.json"
        if refmap not in names:
            fail(f"[forge] jar 内缺少 {refmap}")
        else:
            try:
                json.loads(jars["forge"].read(refmap))
            except json.JSONDecodeError as exc:
                fail(f"[forge] {refmap} 不是合法 JSON：{exc}")

    if "neoforge" in jars:
        text = jars["neoforge"].read("META-INF/neoforge.mods.toml").decode("utf-8")
        if "[[mixins]]" not in text:
            fail("[neoforge] neoforge.mods.toml 缺少 [[mixins]] 段，生产环境不会加载 mixin")

    # assets 由 forge 从 neoforge 的 resources 拷贝，两侧不一致就是漂移
    if len(jars) == 2:
        def assets(module: str) -> set[str]:
            return {n for n in jar_names(jars[module]) if n.startswith("assets/")}

        left, right = assets("neoforge"), assets("forge")
        if not left:
            fail("两侧 jar 的 assets 均为空")
        elif left != right:
            fail(f"两侧 assets 不一致：仅 neoforge 有 {sorted(left - right)}，"
                 f"仅 forge 有 {sorted(right - left)}")

    for jar in jars.values():
        jar.close()

    if errors:
        print("jar 校验未通过：", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print("jar 校验通过：")
    for module, name, _ in targets:
        print(f"  - {name}.jar")
    return 0


if __name__ == "__main__":
    sys.exit(main())
