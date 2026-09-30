import os
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "target"


def normalize_path(path):
    return os.path.normcase(str(path)).replace("\\", "/")


def normalize_report(source, destination, spotbugs=False):
    tree = ET.parse(TARGET / source)

    for node in tree.iter():
        node.tag = node.tag.split("}")[-1]

        if not spotbugs and node.tag == "file":
            path = Path(node.attrib["name"])
            if path.is_absolute():
                path = Path(os.path.relpath(path, ROOT))
            node.set("name", normalize_path(path))

        if spotbugs and node.tag == "SourceLine":
            sourcepath = node.get("sourcepath")
            if sourcepath:
                path = Path("src/main/java") / sourcepath
                node.set("sourcepath", normalize_path(path))

    output = TARGET / destination
    tree.write(output, encoding="utf-8", xml_declaration=True)
    print(f"Created: {output.relative_to(ROOT)}")


normalize_report("pmd.xml", "pmd-diff.xml")
normalize_report("checkstyle-result.xml", "checkstyle-diff.xml")
normalize_report("spotbugsXml.xml", "spotbugs-diff.xml", spotbugs=True)