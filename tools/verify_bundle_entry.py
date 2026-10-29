#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify the bundled offline image entry in a built APK (beta.5 gate G4).

Exit codes: 0 pass or update package SKIP, 2 assertion failure,
3 bad structure, 1 argument/other error.
"""
import argparse
import io
import sys
import zipfile


def fail(code, msg):
    print("FAIL: " + msg)
    return code


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--arch", choices=("arm64", "amd64"), required=True)
    args = ap.parse_args()
    try:
        z = zipfile.ZipFile(args.apk)
    except Exception as e:
        return fail(3, "cannot open APK: %s" % e)

    names = z.namelist()
    if "assets/qqbot-update-pkg" in names:
        print("SKIP: update package has no bundle by design")
        return 0
    if "assets/qqbot-bundle.tar.xz" not in names:
        return fail(3, "missing assets/qqbot-bundle.tar.xz")
    if "assets/qqbot-scripts.zip" not in names:
        return fail(3, "missing assets/qqbot-scripts.zip")

    errors = []
    if len(names) != len(set(names)):
        errors.append("A1 duplicate APK entries")
    bi = z.getinfo("assets/qqbot-bundle.tar.xz")
    if bi.compress_type != zipfile.ZIP_STORED:
        errors.append("A3 bundle is not STORED (method=%d)" % bi.compress_type)
    if bi.compress_size != bi.file_size:
        errors.append("A4 bundle compressed_size=%d != file_size=%d" % (bi.compress_size, bi.file_size))
    try:
        scripts = zipfile.ZipFile(io.BytesIO(z.read("assets/qqbot-scripts.zip")))
    except Exception as e:
        return fail(3, "cannot open assets/qqbot-scripts.zip: %s" % e)
    snames = scripts.namelist()
    if len(snames) != len(set(snames)):
        errors.append("A6 duplicate entries in qqbot-scripts.zip")
    try:
        hint = int(scripts.read("opt/qqbot/BUNDLE_SIZE").decode("ascii").strip())
    except Exception as e:
        errors.append("A7 missing/invalid opt/qqbot/BUNDLE_SIZE: %s" % e)
        hint = None
    try:
        arch = scripts.read("opt/qqbot/BUNDLE_ARCH").decode("ascii").strip()
    except Exception as e:
        errors.append("A8 missing/invalid opt/qqbot/BUNDLE_ARCH: %s" % e)
        arch = None
    if hint is not None and hint != bi.file_size:
        errors.append("A7 BUNDLE_SIZE=%d != bundle.file_size=%d" % (hint, bi.file_size))
    if arch is not None and arch != args.arch:
        errors.append("A8 BUNDLE_ARCH=%r != --arch %r" % (arch, args.arch))

    if errors:
        for e in errors:
            print("FAIL: " + e)
        return 2
    print("PASS: A1-A8; bundle=%d bytes STORED; BUNDLE_SIZE=%d; BUNDLE_ARCH=%s" % (bi.file_size, hint, arch))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as e:
        print("FAIL: %s" % e)
        sys.exit(1)
