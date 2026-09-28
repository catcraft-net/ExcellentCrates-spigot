#!/usr/bin/env python3
"""Build the disposable-server test plugin; never install it on a live server."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--nightcore', type=Path, required=True)
parser.add_argument('--maven-repo', type=Path, default=Path.home() / '.m2/repository')
parser.add_argument('--javac', default='javac')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
repo = args.maven_repo.resolve()
candidate = root / 'target/ExcellentCrates-6.6.1-catcraft.3.jar'
api_dir = repo / 'org/spigotmc/spigot-api/1.21.10-R0.1-SNAPSHOT'
api = api_dir / 'spigot-api-1.21.10-R0.1-SNAPSHOT.jar'
junit = repo / 'junit/junit/4.13.2/junit-4.13.2.jar'
hamcrest = repo / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'
dependencies = [candidate, args.nightcore.resolve(), api, junit, hamcrest]
dependencies += [path for path in (repo / 'net/md-5/bungeecord-chat').rglob('*.jar')
                 if not path.name.endswith(('-sources.jar', '-javadoc.jar'))]
for dependency in dependencies:
    if not dependency.is_file():
        parser.error(f'Missing dependency: {dependency}; run Maven verify first.')
output = root / 'target/SearchIntegration.jar'
with tempfile.TemporaryDirectory(prefix='crate-search-tests-') as temporary:
    classes = Path(temporary)
    sources = sorted((root / 'src/integration-test/java').rglob('*.java'))
    subprocess.run([args.javac, '--release', '21', '-cp', os.pathsep.join(map(str, dependencies)),
                    '-d', str(classes), *map(str, sources)], check=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as jar:
        for source in sorted(classes.rglob('*.class')):
            jar.write(source, source.relative_to(classes).as_posix())
        for dependency in [junit, hamcrest]:
            with zipfile.ZipFile(dependency) as library:
                for entry in library.infolist():
                    if entry.filename.endswith('.class'):
                        jar.writestr(entry.filename, library.read(entry))
        jar.writestr('plugin.yml', 'name: SearchIntegration\nversion: 1.0\n'
                     'main: su.nightexpress.excellentcrates.editor.crate.SearchIntegrationPlugin\n'
                     'api-version: "1.21"\ndepend: [ExcellentCrates, nightcore]\n')
print(output)
