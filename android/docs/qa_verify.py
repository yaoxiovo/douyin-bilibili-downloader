#!/usr/bin/env python3
import os
import glob
import re
import sys
import json
import xml.etree.ElementTree as ET

def main():
    report = {
        "timestamp": "2026-10-25T02:41:20Z",
        "agent": "QA & Release Pipeline",
        "status": "SUCCESS",
        "summary": {},
        "details": {
            "xml_files": [],
            "java_files": [],
            "gradle_files": [],
            "workflow_files": []
        },
        "errors": []
    }

    # 1. XML Files Check
    xml_files = glob.glob("/root/douyin-bilibili-downloader/android/**/*.xml", recursive=True)
    for f in sorted(xml_files):
        try:
            tree = ET.parse(f)
            root = tree.getroot()
            report["details"]["xml_files"].append({
                "file": os.path.relpath(f, "/root/douyin-bilibili-downloader"),
                "root_tag": root.tag,
                "status": "PASS"
            })
        except Exception as e:
            report["details"]["xml_files"].append({
                "file": os.path.relpath(f, "/root/douyin-bilibili-downloader"),
                "status": "FAIL",
                "error": str(e)
            })
            report["errors"].append(f"XML parse error in {f}: {e}")

    # 2. Java Files Check
    java_files = glob.glob("/root/douyin-bilibili-downloader/android/**/*.java", recursive=True)
    for f in sorted(java_files):
        with open(f, "r", encoding="utf-8") as jf:
            content = jf.read()
        
        # Package validation
        pkg_match = re.search(r"package\s+([a-zA-Z0-9_.]+);", content)
        if not pkg_match:
            report["errors"].append(f"Missing package declaration in {f}")
            continue
        pkg = pkg_match.group(1)
        
        # Class / Interface / Enum validation
        expected_type = os.path.basename(f).replace(".java", "")
        type_match = re.search(r"\b(class|interface|enum|@interface)\s+" + re.escape(expected_type) + r"\b", content)
        if not type_match:
            report["errors"].append(f"Type {expected_type} not declared properly in {f}")
        
        # Brace balance check
        open_b = content.count("{")
        close_b = content.count("}")
        if open_b != close_b:
            report["errors"].append(f"Mismatched braces in {f}: {open_b} vs {close_b}")
            status = "FAIL"
        else:
            status = "PASS"
            
        report["details"]["java_files"].append({
            "file": os.path.relpath(f, "/root/douyin-bilibili-downloader"),
            "class_name": expected_type,
            "package": pkg,
            "lines": len(content.splitlines()),
            "status": status
        })

    # 3. Gradle Config Files Check
    gradle_files = [
        "/root/douyin-bilibili-downloader/android/build.gradle",
        "/root/douyin-bilibili-downloader/android/app/build.gradle",
        "/root/douyin-bilibili-downloader/android/settings.gradle",
        "/root/douyin-bilibili-downloader/android/gradle.properties",
        "/root/douyin-bilibili-downloader/android/gradle/wrapper/gradle-wrapper.properties",
        "/root/douyin-bilibili-downloader/android/gradle/wrapper/gradle-wrapper.jar"
    ]
    for gf in gradle_files:
        if os.path.exists(gf) and os.path.getsize(gf) > 0:
            report["details"]["gradle_files"].append({
                "file": os.path.relpath(gf, "/root/douyin-bilibili-downloader"),
                "size_bytes": os.path.getsize(gf),
                "status": "PASS"
            })
        else:
            report["errors"].append(f"Missing or empty file: {gf}")

    # 4. GitHub Actions Workflow Check
    wf = "/root/douyin-bilibili-downloader/.github/workflows/build-apk.yml"
    if os.path.exists(wf):
        with open(wf, "r", encoding="utf-8") as wff:
            wf_content = wff.read()
        report["details"]["workflow_files"].append({
            "file": os.path.relpath(wf, "/root/douyin-bilibili-downloader"),
            "lines": len(wf_content.splitlines()),
            "status": "PASS" if "assembleDebug" in wf_content and "actions/checkout" in wf_content else "WARN"
        })
    else:
        report["errors"].append(f"Missing GitHub Actions workflow: {wf}")

    # Summarize
    report["summary"] = {
        "xml_count": len(report["details"]["xml_files"]),
        "java_count": len(report["details"]["java_files"]),
        "gradle_count": len(report["details"]["gradle_files"]),
        "total_errors": len(report["errors"])
    }

    if len(report["errors"]) > 0:
        report["status"] = "FAILED"

    report_path = "/root/douyin-bilibili-downloader/android/docs/QA_RELEASE_REPORT.json"
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as out:
        json.dump(report, out, indent=2, ensure_ascii=False)

    print("=== QA VERIFICATION RESULTS ===")
    print(f"Status: {report['status']}")
    print(f"XML Files Checked: {report['summary']['xml_count']}")
    print(f"Java Files Checked: {report['summary']['java_count']}")
    print(f"Gradle Files Checked: {report['summary']['gradle_count']}")
    print(f"Total Errors Found: {report['summary']['total_errors']}")
    if report["errors"]:
        for err in report["errors"]:
            print(f"  [ERROR] {err}")
        sys.exit(1)
    else:
        print("All quality gates passed perfectly!")

if __name__ == "__main__":
    main()
