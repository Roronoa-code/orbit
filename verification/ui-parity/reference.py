"""Read-only approved APK extraction; fixture exists only in this local comparison page."""
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parent
with ZipFile(root.parents[1] / "dist/Orbit.apk") as apk:
    html = apk.read("assets/index.html").decode()
fixture = (root / "reference-fixture.json").read_text()
script = '<script>window.OrbitHealth={snapshot:k=>JSON.stringify({revision:"parity",available:true,permitted:true,data:k==="parity"?null:' + fixture + '}),load(){}};</script>'
(root / "reference.html").write_text(html.replace("<head>", "<head>" + script, 1), encoding="utf-8")
