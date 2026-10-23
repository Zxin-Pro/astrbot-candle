#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""无 Mac 环境下的 Xcode 工程生成器。

这台机器是 Windows，装不了 xcodegen，所以用纯 Python 直接吐一份
ios/AstrBotIOS.xcodeproj/project.pbxproj（老式 NeXTSTEP ASCII plist，
objectVersion 56 / compatibilityVersion "Xcode 14.0"）。

设计要点：
  * UUID 用 sha1("类型|路径")[:24] 取大写十六进制，同一路径稳定出同一个 UUID，
    重复生成的结果字节级一致（便于 diff 和 CI 缓存）。类型前缀是为了让同一个
    路径在 fileRef / buildFile / group 三种角色下不撞号。
  * .swift 全部进 PBXSourcesBuildPhase。
  * Resources/web 与 shared(ios/shared) 用「folder reference」进包，
    PBXFileReference 里 lastKnownFileType = folder，Xcode 会整目录原样拷进
    .app，Bundle 里就是 web/ 与 shared/，正好对上代码里
    Bundle.main.resourceURL.appendingPathComponent("web") 的读法。
  * 写完立刻跑 validate() 自校验；--check 只校验不写盘。

用法：
    python ios/tools/gen_xcodeproj.py            # 生成 + 自校验
    python ios/tools/gen_xcodeproj.py --check    # 只校验磁盘上已有的工程
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
IOS = REPO / "ios"
SRC = IOS / "AstrBotIOS"
PROJ = IOS / "AstrBotIOS.xcodeproj"
PBXPROJ = PROJ / "project.pbxproj"
WORKSPACE = PROJ / "project.xcworkspace" / "contents.xcworkspacedata"
SCHEME = PROJ / "xcshareddata" / "xcschemes" / "AstrBotIOS.xcscheme"

APP_NAME = "AstrBotIOS"
BUNDLE_ID = "com.astrbot.ios"
DEPLOYMENT_TARGET = "16.0"   # NavigationStack / .topBarLeading 是 iOS 16+ API
INFOPLIST_REL = "AstrBotIOS/Resources/Info.plist"
OBJ_VERSION = 56
COMPAT_VERSION = "Xcode 14.0"

GROUP_ORDER = ["App", "Bridge", "Net", "UI", "Resources", "Products"]

_IDS: dict[str, str] = {}


def uid(kind: str, path: str) -> str:
    """稳定 UUID：sha1(类型|路径) 前 24 位大写十六进制。"""
    raw = hashlib.sha1(f"{kind}|{path}".encode("utf-8")).hexdigest()[:24].upper()
    prev = _IDS.get(raw)
    label = f"{kind}|{path}"
    if prev is not None and prev != label:
        raise SystemExit(f"[gen] UUID 冲突: {label} 与 {prev}")
    _IDS[raw] = label
    return raw


def q(value: object) -> str:
    """pbxproj 里的字符串引用规则：安全字符直接裸写，否则加双引号。"""
    s = str(value)
    if s and re.fullmatch(r"[A-Za-z0-9_$./]+", s):
        return s
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def scan_swift() -> list[Path]:
    out = []
    for p in sorted(SRC.rglob("*.swift"), key=lambda x: x.relative_to(SRC).as_posix().lower()):
        rel = p.relative_to(SRC)
        if any(part.startswith(".") for part in rel.parts):
            continue
        out.append(p)
    return out


def collect_layout(swift: list[Path]) -> dict:
    """把扫描结果整理成 pbxproj 需要的那几个集合。"""
    files = []
    groups: dict[str, list[Path]] = {g: [] for g in GROUP_ORDER}
    loose = []
    for p in swift:
        rel = p.relative_to(SRC)
        parts = rel.parts
        if len(parts) > 1 and parts[0] in groups:
            groups[parts[0]].append(p)
        else:
            loose.append(p)
        files.append((rel.as_posix(), p.name))

    assets = SRC / "Resources" / "Assets.xcassets"
    web = SRC / "Resources" / "web"
    shared = IOS / "shared"
    info = SRC / "Resources" / "Info.plist"
    return {
        "swift": files,
        "groups": groups,
        "loose": loose,
        "assets": assets if assets.is_dir() else None,
        "web": web,
        "web_ok": web.is_dir(),
        "shared": shared if shared.is_dir() else None,
        "info": info if info.is_file() else None,
    }


# --------------------------------------------------------------------------
# pbxproj 文本拼装
# --------------------------------------------------------------------------

def build_project_text(layout: dict) -> tuple[str, dict]:
    swift = layout["swift"]
    ids = {
        "project": uid("project", APP_NAME),
        "target": uid("target", APP_NAME),
        "product": uid("product", APP_NAME + ".app"),
        "main_group": uid("group", "<main>"),
        "products": uid("group", "Products"),
        "src_phase": uid("phase", "sources"),
        "fw_phase": uid("phase", "frameworks"),
        "res_phase": uid("phase", "resources"),
        "proj_cfg": uid("configlist", "project"),
        "target_cfg": uid("configlist", "target"),
    }
    for g in GROUP_ORDER:
        if g != "Products":
            ids["group_" + g] = uid("group", g)

    # 文件引用 / 构建文件
    refs: list[tuple[str, str, str]] = []   # (uuid, label, 单行定义体)
    builds: list[tuple[str, str, str, str]] = []  # (uuid, label, fileRef, phase)

    for rel, name in swift:
        fid = uid("fileref", rel)
        bid = uid("buildfile", rel)
        refs.append((fid, name,
                     f'lastKnownFileType = sourcecode.swift; path = {q(name)}; sourceTree = "<group>"'))
        builds.append((bid, name, fid, "Sources"))

    if layout["assets"]:
        rel = "Resources/Assets.xcassets"
        fid = uid("fileref", rel)
        bid = uid("buildfile", rel)
        refs.append((fid, "Assets.xcassets",
                     'lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = "<group>"'))
        builds.append((bid, "Assets.xcassets", fid, "Resources"))

    fid_web = uid("fileref", "Resources/web")
    refs.append((fid_web, "web", 'lastKnownFileType = folder; path = web; sourceTree = "<group>"'))
    builds.append((uid("buildfile", "Resources/web"), "web", fid_web, "Resources"))

    fid_shared = uid("fileref", "shared")
    refs.append((fid_shared, "shared",
                 'lastKnownFileType = folder; name = shared; path = shared; sourceTree = SOURCE_ROOT'))
    builds.append((uid("buildfile", "shared"), "shared", fid_shared, "Resources"))

    fid_info = uid("fileref", "Resources/Info.plist")
    refs.append((fid_info, "Info.plist",
                 'lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"'))

    refs.append((ids["product"], APP_NAME + ".app",
                 f'explicitFileType = wrapper.application; includeInIndex = 0; '
                 f'path = {APP_NAME}.app; sourceTree = BUILT_PRODUCTS_DIR'))

    lines: list[str] = ["// !$*UTF8*$!", "{", "\tarchiveVersion = 1;", "\tclasses = {", "\t};",
                        f"\tobjectVersion = {OBJ_VERSION};", "\tobjects = {"]

    def add_section(name: str, body: list[str]) -> None:
        if not body:
            return
        lines.append("")
        lines.append(f"/* Begin {name} section */")
        lines.extend(body)
        lines.append(f"/* End {name} section */")

    # ---- PBXBuildFile ----
    body = []
    for bid, label, fid, phase in sorted(builds, key=lambda t: (t[1].lower(), t[3])):
        body.append(f"\t\t{bid} /* {label} in {phase} */ = {{isa = PBXBuildFile; "
                    f"fileRef = {fid} /* {label} */; }};")
    add_section("PBXBuildFile", body)

    # ---- PBXFileReference ----
    body = []
    for fid, label, tail in sorted(refs, key=lambda t: t[1].lower()):
        body.append(f"\t\t{fid} /* {label} */ = {{isa = PBXFileReference; {tail}; }};")
    add_section("PBXFileReference", body)

    # ---- PBXFrameworksBuildPhase ----
    add_section("PBXFrameworksBuildPhase", [
        f"\t\t{ids['fw_phase']} /* Frameworks */ = {{",
        "\t\t\tisa = PBXFrameworksBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
        "\t\t\t);",
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
        "\t\t};",
    ])

    # ---- PBXGroup ----
    body = []
    for g in GROUP_ORDER:
        if g == "Products":
            continue
        entries = []
        if g == "Resources":
            if layout["assets"]:
                entries.append((uid("fileref", "Resources/Assets.xcassets"), "Assets.xcassets"))
            if layout["info"]:
                entries.append((fid_info, "Info.plist"))
            entries.append((fid_web, "web"))
            entries.append((fid_shared, "shared"))
        else:
            for p in layout["groups"][g]:
                rel = p.relative_to(SRC).as_posix()
                entries.append((uid("fileref", rel), p.name))
        if not entries:
            continue
        body.append(f"\t\t{ids['group_' + g]} /* {g} */ = {{")
        body.append("\t\t\tisa = PBXGroup;")
        body.append("\t\t\tchildren = (")
        for fid, label in entries:
            body.append(f"\t\t\t\t{fid} /* {label} */,")
        body.append("\t\t\t);")
        if g == "Resources":
            body.append("\t\t\tpath = AstrBotIOS/Resources;")
        else:
            body.append(f"\t\t\tpath = {q(g)};")
        body.append("\t\t\tsourceTree = \"<group>\";")
        body.append("\t\t};")

    body.append(f"\t\t{ids['products']} /* Products */ = {{")
    body.append("\t\t\tisa = PBXGroup;")
    body.append("\t\t\tchildren = (")
    body.append(f"\t\t\t\t{ids['product']} /* {APP_NAME}.app */,")
    body.append("\t\t\t);")
    body.append("\t\t\tname = Products;")
    body.append("\t\t\tsourceTree = \"<group>\";")
    body.append("\t\t};")

    body.append(f"\t\t{ids['main_group']} /* {APP_NAME} */ = {{")
    body.append("\t\t\tisa = PBXGroup;")
    body.append("\t\t\tchildren = (")
    for g in GROUP_ORDER:
        if g == "Products":
            body.append(f"\t\t\t\t{ids['products']} /* Products */,")
        elif any(layout["groups"][g]) or g == "Resources":
            body.append(f"\t\t\t\t{ids['group_' + g]} /* {g} */,")
    for p in layout["loose"]:
        rel = p.relative_to(SRC).as_posix()
        body.append(f"\t\t\t\t{uid('fileref', rel)} /* {p.name} */,")
    body.append("\t\t\t);")
    body.append("\t\t\tsourceTree = \"<group>\";")
    body.append("\t\t};")
    add_section("PBXGroup", body)

    # ---- PBXNativeTarget ----
    add_section("PBXNativeTarget", [
        f"\t\t{ids['target']} /* {APP_NAME} */ = {{",
        "\t\t\tisa = PBXNativeTarget;",
        f"\t\t\tbuildConfigurationList = {ids['target_cfg']} /* Build configuration list for PBXNativeTarget \"{APP_NAME}\" */;",
        "\t\t\tbuildPhases = (",
        f"\t\t\t\t{ids['src_phase']} /* Sources */,",
        f"\t\t\t\t{ids['fw_phase']} /* Frameworks */,",
        f"\t\t\t\t{ids['res_phase']} /* Resources */,",
        "\t\t\t);",
        "\t\t\tbuildRules = (",
        "\t\t\t);",
        "\t\t\tdependencies = (",
        "\t\t\t);",
        f"\t\t\tname = {APP_NAME};",
        f"\t\t\tproductName = {APP_NAME};",
        f"\t\t\tproductReference = {ids['product']} /* {APP_NAME}.app */;",
        "\t\t\tproductType = \"com.apple.product-type.application\";",
        "\t\t};",
    ])

    # ---- PBXProject ----
    add_section("PBXProject", [
        f"\t\t{ids['project']} /* Project object */ = {{",
        "\t\t\tisa = PBXProject;",
        "\t\t\tattributes = {",
        "\t\t\t\tBuildIndependentTargetsInParallel = 1;",
        "\t\t\t\tLastUpgradeCheck = 1400;",
        "\t\t\t\tTargetAttributes = {",
        f"\t\t\t\t\t{ids['target']} = {{",
        "\t\t\t\t\t\tCreatedOnToolsVersion = 14.0;",
        "\t\t\t\t\t};",
        "\t\t\t\t};",
        "\t\t\t};",
        f"\t\t\tbuildConfigurationList = {ids['proj_cfg']} /* Build configuration list for PBXProject \"{APP_NAME}\" */;",
        f"\t\t\tcompatibilityVersion = {q(COMPAT_VERSION)};",
        "\t\t\tdevelopmentRegion = zh_CN;",
        "\t\t\thasScannedForEncodings = 0;",
        "\t\t\tknownRegions = (",
        "\t\t\t\tzh_CN,",
        "\t\t\t\tBase,",
        "\t\t\t);",
        f"\t\t\tmainGroup = {ids['main_group']} /* {APP_NAME} */;",
        f"\t\t\tproductRefGroup = {ids['products']} /* Products */;",
        "\t\t\tprojectDirPath = \"\";",
        "\t\t\tprojectRoot = \"\";",
        "\t\t\ttargets = (",
        f"\t\t\t\t{ids['target']} /* {APP_NAME} */,",
        "\t\t\t);",
        "\t\t};",
    ])

    # ---- PBXResourcesBuildPhase ----
    body = [
        f"\t\t{ids['res_phase']} /* Resources */ = {{",
        "\t\t\tisa = PBXResourcesBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
    ]
    for bid, label, fid, phase in sorted(builds, key=lambda t: (t[1].lower(), t[3])):
        if phase == "Resources":
            body.append(f"\t\t\t\t{bid} /* {label} in Resources */,")
    body += ["\t\t\t);", "\t\t\trunOnlyForDeploymentPostprocessing = 0;", "\t\t};"]
    add_section("PBXResourcesBuildPhase", body)

    # ---- PBXSourcesBuildPhase ----
    body = [
        f"\t\t{ids['src_phase']} /* Sources */ = {{",
        "\t\t\tisa = PBXSourcesBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
    ]
    for bid, label, fid, phase in sorted(builds, key=lambda t: (t[1].lower(), t[3])):
        if phase == "Sources":
            body.append(f"\t\t\t\t{bid} /* {label} in Sources */,")
    body += ["\t\t\t);", "\t\t\trunOnlyForDeploymentPostprocessing = 0;", "\t\t};"]
    add_section("PBXSourcesBuildPhase", body)

    # ---- 构建设置 ----
    def setting_lines(settings: list[tuple[str, object]]) -> list[str]:
        out = []
        for key, value in settings:
            if isinstance(value, (list, tuple)):
                out.append(f"\t\t\t\t{key} = (")
                for item in value:
                    out.append(f"\t\t\t\t\t{q(item)},")
                out.append("\t\t\t\t);")
            else:
                out.append(f"\t\t\t\t{key} = {q(value)};")
        return out

    base_common = [
        ("ALWAYS_SEARCH_USER_PATHS", "NO"),
        ("CLANG_ANALYZER_NONNULL", "YES"),
        ("CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION", "YES_AGGRESSIVE"),
        ("CLANG_CXX_LANGUAGE_STANDARD", "gnu++17"),
        ("CLANG_ENABLE_MODULES", "YES"),
        ("CLANG_ENABLE_OBJC_ARC", "YES"),
        ("CLANG_ENABLE_OBJC_WEAK", "YES"),
        ("CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING", "YES"),
        ("CLANG_WARN_BOOL_CONVERSION", "YES"),
        ("CLANG_WARN_COMMA", "YES"),
        ("CLANG_WARN_CONSTANT_CONVERSION", "YES"),
        ("CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS", "YES"),
        ("CLANG_WARN_DIRECT_OBJC_ISA_USAGE", "YES_ERROR"),
        ("CLANG_WARN_DOCUMENTATION_COMMENTS", "YES"),
        ("CLANG_WARN_EMPTY_BODY", "YES"),
        ("CLANG_WARN_ENUM_CONVERSION", "YES"),
        ("CLANG_WARN_INFINITE_RECURSION", "YES"),
        ("CLANG_WARN_INT_CONVERSION", "YES"),
        ("CLANG_WARN_NON_LITERAL_NULL_CONVERSION", "YES"),
        ("CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF", "YES"),
        ("CLANG_WARN_OBJC_LITERAL_CONVERSION", "YES"),
        ("CLANG_WARN_OBJC_ROOT_CLASS", "YES_ERROR"),
        ("CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER", "YES"),
        ("CLANG_WARN_RANGE_LOOP_ANALYSIS", "YES"),
        ("CLANG_WARN_STRICT_PROTOTYPES", "YES"),
        ("CLANG_WARN_SUSPICIOUS_MOVE", "YES"),
        ("CLANG_WARN_UNGUARDED_AVAILABILITY", "YES_AGGRESSIVE"),
        ("CLANG_WARN_UNREACHABLE_CODE", "YES"),
        ("CLANG_WARN__DUPLICATE_METHOD_MATCH", "YES"),
        ("COPY_PHASE_STRIP", "NO"),
        ("ENABLE_STRICT_OBJC_MSGSEND", "YES"),
        ("GCC_C_LANGUAGE_STANDARD", "gnu17"),
        ("GCC_NO_COMMON_BLOCKS", "YES"),
        ("GCC_WARN_64_TO_32_BIT_CONVERSION", "YES"),
        ("GCC_WARN_ABOUT_RETURN_TYPE", "YES_ERROR"),
        ("GCC_WARN_UNDECLARED_SELECTOR", "YES"),
        ("GCC_WARN_UNINITIALIZED_AUTOS", "YES_AGGRESSIVE"),
        ("GCC_WARN_UNUSED_FUNCTION", "YES"),
        ("GCC_WARN_UNUSED_VARIABLE", "YES"),
        ("IPHONEOS_DEPLOYMENT_TARGET", DEPLOYMENT_TARGET),
        ("MTL_FAST_MATH", "YES"),
        ("SDKROOT", "iphoneos"),
        ("SWIFT_STRICT_CONCURRENCY", "minimal"),
        ("SWIFT_VERSION", "5.0"),
    ]

    debug_extra = [
        ("DEBUG_INFORMATION_FORMAT", "dwarf"),
        ("ENABLE_TESTABILITY", "YES"),
        ("ONLY_ACTIVE_ARCH", "YES"),
        ("SWIFT_ACTIVE_COMPILATION_CONDITIONS", "DEBUG"),
        ("SWIFT_OPTIMIZATION_LEVEL", "-Onone"),
    ]
    release_extra = [
        ("DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym"),
        ("ENABLE_NS_ASSERTIONS", "NO"),
        ("SWIFT_COMPILATION_MODE", "wholemodule"),
        ("SWIFT_OPTIMIZATION_LEVEL", "-O"),
        ("VALIDATE_PRODUCT", "YES"),
    ]

    target_common = [
        ("ASSETCATALOG_COMPILER_APPICON_NAME", "AppIcon"),
        ("CODE_SIGN_STYLE", "Automatic"),
        ("ENABLE_USER_SCRIPT_SANDBOXING", "NO"),
        ("GENERATE_INFOPLIST_FILE", "NO"),
        ("INFOPLIST_FILE", INFOPLIST_REL),
        ("LD_RUNPATH_SEARCH_PATHS", ["$(inherited)", "@executable_path/Frameworks"]),
        ("PRODUCT_BUNDLE_IDENTIFIER", BUNDLE_ID),
        ("PRODUCT_NAME", "$(TARGET_NAME)"),
        ("SWIFT_EMIT_LOC_STRINGS", "YES"),
        ("SWIFT_VERSION", "5.0"),
        ("TARGETED_DEVICE_FAMILY", "1,2"),
    ]

    configs = [
        ("project", "Debug", base_common + debug_extra),
        ("project", "Release", base_common + release_extra),
        ("target", "Debug", target_common),
        ("target", "Release", target_common),
    ]
    body = []
    cfg_ids = {}
    for scope, name, settings in configs:
        cid = uid("buildconfig", f"{scope}/{name}")
        cfg_ids[(scope, name)] = cid
        body.append(f"\t\t{cid} /* {name} */ = {{")
        body.append("\t\t\tisa = XCBuildConfiguration;")
        body.append("\t\t\tbuildSettings = {")
        body.extend(setting_lines(sorted(settings, key=lambda kv: kv[0])))
        body.append("\t\t\t};")
        body.append(f"\t\t\tname = {name};")
        body.append("\t\t};")
    add_section("XCBuildConfiguration", body)

    # ---- XCConfigurationList ----
    add_section("XCConfigurationList", [
        f"\t\t{ids['proj_cfg']} /* Build configuration list for PBXProject \"{APP_NAME}\" */ = {{",
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        f"\t\t\t\t{cfg_ids[('project', 'Debug')]} /* Debug */,",
        f"\t\t\t\t{cfg_ids[('project', 'Release')]} /* Release */,",
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
        f"\t\t{ids['target_cfg']} /* Build configuration list for PBXNativeTarget \"{APP_NAME}\" */ = {{",
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        f"\t\t\t\t{cfg_ids[('target', 'Debug')]} /* Debug */,",
        f"\t\t\t\t{cfg_ids[('target', 'Release')]} /* Release */,",
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
    ])

    lines.append("\t};")
    lines.append(f"\trootObject = {ids['project']} /* Project object */;")
    lines.append("}")
    return "\n".join(lines) + "\n", ids


WORKSPACE_TEXT = """<?xml version="1.0" encoding="UTF-8"?>
<Workspace
   version = "1.0">
   <FileRef
      location = "self:">
   </FileRef>
</Workspace>
"""

SCHEME_TEXT = """<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1400"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{target}"
               BuildableName = "{app}.app"
               BlueprintName = "{app}"
               ReferencedContainer = "container:{app}.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES"
      shouldAutocreateTestPlan = "YES">
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target}"
            BuildableName = "{app}.app"
            BlueprintName = "{app}"
            ReferencedContainer = "container:{app}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target}"
            BuildableName = "{app}.app"
            BlueprintName = "{app}"
            ReferencedContainer = "container:{app}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""


# --------------------------------------------------------------------------
# 自校验
# --------------------------------------------------------------------------

def _strip_noise(text: str) -> str:
    """去掉注释与字符串字面量，留下纯结构，用来数括号。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _section(text: str, name: str) -> str:
    start = f"/* Begin {name} section */"
    end = f"/* End {name} section */"
    i = text.find(start)
    j = text.find(end)
    if i < 0 or j < 0:
        return ""
    return text[i + len(start):j]


TOKEN_RE = re.compile(r"""
    (?P<ws>\s+)
  | (?P<comment>/\*.*?\*/|//[^\n]*)
  | (?P<str>"(?:[^"\\]|\\.)*")
  | (?P<punct>[{}();=,])
  | (?P<bare>[^\s{}();=,"]+)
""", re.S | re.X)


class PlistError(Exception):
    pass


def parse_openstep_plist(text: str):
    """极简 NeXTSTEP/OpenStep plist 解析器，只为本脚本自校验服务。

    它不认识语义，只把 pbxproj 解成 dict/list/str，用来证明「这份文本真的能被
    按 Xcode 的语法读成一棵树」——括号配平是必要条件，不是充分条件。
    """
    tokens = []
    pos = 0
    while pos < len(text):
        m = TOKEN_RE.match(text, pos)
        if not m:
            raise PlistError(f"无法识别的字符 @ {pos}: {text[pos:pos + 20]!r}")
        pos = m.end()
        kind = m.lastgroup
        if kind in ("ws", "comment"):
            continue
        tokens.append((kind, m.group()))

    idx = 0

    def peek():
        return tokens[idx] if idx < len(tokens) else (None, None)

    def take():
        nonlocal idx
        if idx >= len(tokens):
            raise PlistError("输入提前结束")
        tok = tokens[idx]
        idx += 1
        return tok

    def unquote(s: str) -> str:
        body = s[1:-1]
        out = []
        i = 0
        while i < len(body):
            c = body[i]
            if c == "\\" and i + 1 < len(body):
                nxt = body[i + 1]
                out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(nxt, nxt))
                i += 2
            else:
                out.append(c)
                i += 1
        return "".join(out)

    def parse_value():
        kind, val = take()
        if kind == "punct" and val == "{":
            result = {}
            while True:
                kkind, kval = peek()
                if kkind == "punct" and kval == "}":
                    take()
                    return result
                if kkind is None:
                    raise PlistError("字典没有闭合")
                key = unquote(kval) if kkind == "str" else kval
                take()
                eq = take()
                if eq != ("punct", "="):
                    raise PlistError(f"字典缺 '='，遇到 {eq!r}")
                result[key] = parse_value()
                semi = take()
                if semi != ("punct", ";"):
                    raise PlistError(f"字典项缺 ';'，遇到 {semi!r}")
        if kind == "punct" and val == "(":
            result = []
            while True:
                kkind, kval = peek()
                if kkind == "punct" and kval == ")":
                    take()
                    return result
                if kkind is None:
                    raise PlistError("数组没有闭合")
                result.append(parse_value())
                sep = take()
                if sep == ("punct", ")"):
                    return result
                if sep != ("punct", ","):
                    raise PlistError(f"数组项缺 ','，遇到 {sep!r}")
                nkind, nval = peek()
                if nkind == "punct" and nval == ")":  # 允许尾逗号
                    take()
                    return result
        if kind == "str":
            return unquote(val)
        if kind == "bare":
            return val
        raise PlistError(f"意外的 token: {kind} {val!r}")

    kind, val = peek()
    if (kind, val) != ("punct", "{"):
        raise PlistError("根节点不是字典")
    root = parse_value()
    if idx != len(tokens):
        raise PlistError(f"根字典之后还有 {len(tokens) - idx} 个 token")
    return root


UUID_RE = re.compile(r"(?<![0-9A-F])[0-9A-F]{24}(?![0-9A-F])")
DEF_RE = re.compile(r"^\t\t([0-9A-F]{24}) /\* [^\n]*? \*/ = \{", re.M)


def validate(path: Path) -> dict:
    """结构自校验：括号配平 / UUID 有定义 / Swift 进出 Sources / Info.plist 挂上。"""
    if not path.is_file():
        raise SystemExit(f"[check] 工程文件不存在: {path}")
    text = path.read_text(encoding="utf-8")
    checks: list[tuple[str, bool, str]] = []

    # (a) 括号配平
    plain = _strip_noise(text)
    for opener, closer, name in (("{", "}", "braces"), ("(", ")", "parens")):
        depth = 0
        bad = False
        for ch in plain:
            if ch == opener:
                depth += 1
            elif ch == closer:
                depth -= 1
                if depth < 0:
                    bad = True
                    break
        ok = (not bad) and depth == 0
        checks.append((f"{name}_balanced", ok,
                       f"{opener}{closer} 深度归零" if ok else f"{opener}{closer} 不配平（余 {depth}）"))

    # (b) UUID 定义完整
    defined = DEF_RE.findall(text)
    used = set(UUID_RE.findall(text))
    missing = sorted(used - set(defined))
    dup = len(defined) != len(set(defined))
    checks.append(("uuids_defined", not missing and not dup,
                   f"{len(defined)} 个定义 / {len(used)} 个引用" if not missing
                   else f"未定义引用: {', '.join(missing[:3])}"))
    checks.append(("uuids_unique", not dup,
                   "无重复定义" if not dup else "存在重复定义"))

    # (c) 每个 .swift 在 Sources 里出现且仅一次
    src_section = _section(text, "PBXSourcesBuildPhase")
    files_block = re.search(r"files = \(\n(.*?)\n\t\t\t\);", src_section, re.S)
    src_build_ids = UUID_RE.findall(files_block.group(1)) if files_block else []
    bf_map = dict(re.findall(
        r"^\t\t([0-9A-F]{24}) /\* [^\n]*? \*/ = \{isa = PBXBuildFile; fileRef = ([0-9A-F]{24})",
        _section(text, "PBXBuildFile"), re.M))
    ref_path = dict(re.findall(
        r"^\t\t([0-9A-F]{24}) /\* [^\n]*? \*/ = \{isa = PBXFileReference;[^\n]*?path = ([^;]+);",
        text, re.M))

    resolved = []
    for bid in src_build_ids:
        fid = bf_map.get(bid)
        raw = ref_path.get(fid, "") if fid else ""
        resolved.append(raw.strip().strip('"'))

    disk = sorted(p.name for p in scan_swift())
    from collections import Counter
    ok_src = Counter(disk) == Counter(resolved) and len(disk) == len(src_build_ids)
    detail = f"{len(resolved)} 个构建条目 / 磁盘 {len(disk)} 个 .swift"
    if not ok_src:
        extra = sorted(set(resolved) - set(disk))
        lack = sorted(set(disk) - set(resolved))
        detail += f"（多出 {extra} / 缺少 {lack}）"
    checks.append(("sources_match_swift", ok_src, detail))

    # (d) Info.plist 路径
    m = re.search(r"(?m)^[ \t]*INFOPLIST_FILE = ([^;]+);", text)
    value = m.group(1).strip().strip('"') if m else ""
    plist_ok = value == INFOPLIST_REL and (IOS / value).is_file()
    checks.append(("infoplist_file", plist_ok,
                   f"INFOPLIST_FILE = {value}" if plist_ok else f"INFOPLIST_FILE 不匹配: {value!r}"))

    # (e) 额外：web / shared 是否以 folder reference 进了 Resources
    res_section = _section(text, "PBXResourcesBuildPhase")
    res_block = re.search(r"files = \(\n(.*?)\n\t\t\t\);", res_section, re.S)
    res_ids = UUID_RE.findall(res_block.group(1)) if res_block else []
    res_names = set()
    for bid in res_ids:
        fid = bf_map.get(bid)
        if fid:
            res_names.add(ref_path.get(fid, "").strip().strip('"'))
    want = {"web", "shared"}
    folders_ok = want <= res_names
    checks.append(("resource_folders", folders_ok,
                   f"Resources phase: {', '.join(sorted(res_names))}" if folders_ok
                   else f"缺 folder reference: {', '.join(sorted(want - res_names))}"))

    # (g) 额外：整份文本能按 OpenStep plist 语法解析成一棵树
    try:
        tree = parse_openstep_plist(text)
        objects = tree.get("objects", {})
        root_obj = objects.get(tree.get("rootObject", ""), {})
        parsed_ok = (isinstance(objects, dict) and bool(objects)
                     and root_obj.get("isa") == "PBXProject"
                     and str(tree.get("objectVersion")) == str(OBJ_VERSION))
        parse_detail = (f"{len(objects)} 个 object，rootObject -> PBXProject，"
                        f"objectVersion={tree.get('objectVersion')}") if parsed_ok else "结构不完整"
    except PlistError as exc:
        parse_detail = f"解析失败: {exc}"
        parsed_ok = False
    checks.append(("plist_parseable", parsed_ok, parse_detail))

    # (f) 额外：scheme 的 BlueprintIdentifier 指向真实 target
    target_m = re.search(
        r"^\t\t([0-9A-F]{24}) /\* " + APP_NAME + r" \*/ = \{\n\t\t\tisa = PBXNativeTarget;",
        text, re.M)
    target_id = target_m.group(1) if target_m else ""
    scheme_ok = False
    if SCHEME.is_file() and target_id:
        scheme_text = SCHEME.read_text(encoding="utf-8")
        scheme_ok = (f'BlueprintIdentifier = "{target_id}"' in scheme_text
                     and 'BuildableName = "' + APP_NAME + '.app"' in scheme_text)
    checks.append(("scheme_blueprint", scheme_ok,
                   "scheme 指向本 target" if scheme_ok else "scheme 缺失或 BlueprintIdentifier 不对"))

    ok = all(c[1] for c in checks)
    return {
        "ok": ok,
        "checks": checks,
        "objects": len(set(defined)),
        "swift": len(disk),
        "bytes": path.stat().st_size,
        "errors": [name for name, good, _ in checks if not good],
    }


def print_report(report: dict, prefix: str) -> None:
    for name, good, detail in report["checks"]:
        flag = "OK  " if good else "FAIL"
        print(f"[{prefix}] 校验 {flag} {name}: {detail}")


# --------------------------------------------------------------------------

def generate(write: bool = True) -> dict:
    swift = scan_swift()
    if not swift:
        raise SystemExit(f"[gen] {SRC} 下没扫到任何 .swift，工程会是空的，先确认路径")

    layout = collect_layout(swift)
    if not layout["web_ok"]:
        print(f"[gen] 警告: {layout['web'].relative_to(REPO).as_posix()} 不存在，"
              f"仍会写 folder reference，但打出的包没有网页控制台。先跑 python ios/tools/sync_web.py",
              file=sys.stderr)
    if not layout["assets"]:
        print("[gen] 提示: Resources/Assets.xcassets 不存在，本次不挂 AppIcon 资源目类"
              "（跑 python ios/tools/gen_appicon.py 可生成）", file=sys.stderr)
    if not layout["info"]:
        raise SystemExit("[gen] 缺少 AstrBotIOS/Resources/Info.plist")
    if not layout["shared"]:
        print("[gen] 警告: ios/shared 不存在，App 包里不会有 shared/AndroidShim.js", file=sys.stderr)

    text, ids = build_project_text(layout)
    if write:
        PROJ.mkdir(parents=True, exist_ok=True)
        # 固定 LF：路径一律 utf-8、"newline=\n"，别让 Windows 写 CRLF
        with PBXPROJ.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        WORKSPACE.parent.mkdir(parents=True, exist_ok=True)
        with WORKSPACE.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write(WORKSPACE_TEXT)
        SCHEME.parent.mkdir(parents=True, exist_ok=True)
        with SCHEME.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write(SCHEME_TEXT.replace("{target}", ids["target"]).replace("{app}", APP_NAME))

    report = validate(PBXPROJ)
    report["ids"] = ids
    return report


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="生成 ios/AstrBotIOS.xcodeproj（无 xcodegen 环境）")
    parser.add_argument("--check", action="store_true", help="只校验磁盘上已有的工程，不写盘")
    args = parser.parse_args(argv)

    if args.check:
        report = validate(PBXPROJ)
        print(f"[check] {PBXPROJ.relative_to(REPO).as_posix()} "
              f"{report['bytes']} 字节 / {report['objects']} 个 object / {report['swift']} 个 .swift")
        print_report(report, "check")
        if not report["ok"]:
            print(f"CHECK FAILED  {', '.join(report['errors'])}", file=sys.stderr)
            return 1
        print(f"CHECK OK  swift={report['swift']} objects={report['objects']} "
              f"checks={sum(1 for c in report['checks'] if c[1])}/{len(report['checks'])}")
        return 0

    report = generate(write=True)
    print(f"[gen] 写入 {PBXPROJ.relative_to(REPO).as_posix()} ({report['bytes']} 字节)")
    print(f"[gen] 写入 {WORKSPACE.relative_to(REPO).as_posix()}")
    print(f"[gen] 写入 {SCHEME.relative_to(REPO).as_posix()}")
    print(f"[gen] sources: {report['swift']} 个 .swift，全部进 Sources phase，"
          f"IPHONEOS_DEPLOYMENT_TARGET={DEPLOYMENT_TARGET}")
    print_report(report, "gen")
    if not report["ok"]:
        print(f"GENERATION FAILED  {', '.join(report['errors'])}", file=sys.stderr)
        return 1
    print(f"GENERATED OK  swift={report['swift']} objects={report['objects']} "
          f"checks={sum(1 for c in report['checks'] if c[1])}/{len(report['checks'])} "
          f"bytes={report['bytes']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
