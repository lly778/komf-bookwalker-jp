$ErrorActionPreference='Stop'
$java = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else {
    (Get-Item 'work/jdk21-runtime/*/bin/java.exe' | Select-Object -First 1).FullName
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JDK 21 was not found' }
$stage=(Resolve-Path 'work/komf-jp-build').Path
$officialJar=Join-Path $stage 'komf-2.0.1.jar'
$compiler=(@('kotlin-compiler-embeddable-2.4.20-RC2.jar','kotlin-stdlib-2.4.20-RC2.jar','kotlin-script-runtime-2.4.20-RC2.jar','kotlin-reflect-1.6.10.jar','kotlin-daemon-embeddable-2.4.20-RC2.jar','kotlin-build-tools-api-2.4.20-RC2.jar','kotlinx-coroutines-core-jvm-1.8.0.jar','annotations-26.0.2.jar') | ForEach-Object {Join-Path $stage $_}) -join ';'
$out=Join-Path $stage 'compiled-search-patch'
$testOut=Join-Path $stage 'compiled-search-probe'
New-Item -ItemType Directory -Path $out,$testOut -Force | Out-Null
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 -classpath $officialJar -d $out "$stage/BookWalkerMetadataProvider.kt" "$stage/BookWalkerStoreSearch.kt" "$stage/DmmBooksFallback.kt" "$stage/EbookJapanFallback.kt" "$stage/ProvidersModule.kt" "$stage/MetadataService.kt"
if ($LASTEXITCODE) { throw 'Compilation failed' }
$draft=Join-Path $stage 'komf-dmm-jp-2.0.1.jar'
Copy-Item -LiteralPath $officialJar -Destination $draft -Force
& (Join-Path (Split-Path $java) 'jar.exe') uf $draft -C $out snd
if ($LASTEXITCODE) { throw 'JAR update failed' }
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 "-Xfriend-paths=$out" -classpath "$out;$draft" -d $testOut "$stage/LocalProviderProbe.kt" "$stage/StoreSearchProbe.kt" "$stage/PublicationStatusProbe.kt" "$stage/DmmBooksProbe.kt"
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
$catalog = Join-Path $stage 'catalog.sqlite'
$previousCatalog = $env:KOMF_BOOKWALKER_JP_DB
$previousIndexEnabled = $env:KOMF_BOOKWALKER_JP_INDEX_ENABLED
try {
    $env:KOMF_BOOKWALKER_JP_DB = $catalog
    $env:KOMF_BOOKWALKER_JP_INDEX_ENABLED = 'false'
    & $java -cp "$testOut;$draft" snd.komf.providers.bookwalker.StoreSearchProbeKt
    if ($LASTEXITCODE) { throw 'Store search probe failed' }
    & $java -cp "$testOut;$draft" DmmBooksProbeKt
    if ($LASTEXITCODE) { throw 'DMM Books probe failed' }

    if (Test-Path -LiteralPath $catalog -PathType Leaf) {
        $env:KOMF_BOOKWALKER_JP_INDEX_ENABLED = 'true'
        & $java -cp "$testOut;$draft" LocalProviderProbeKt
        if ($LASTEXITCODE) { throw 'Provider probe failed' }
        & $java -cp "$testOut;$draft" snd.komf.providers.bookwalker.PublicationStatusProbeKt
        if ($LASTEXITCODE) { throw 'Publication status probe failed' }
    }
} finally {
    $env:KOMF_BOOKWALKER_JP_DB = $previousCatalog
    $env:KOMF_BOOKWALKER_JP_INDEX_ENABLED = $previousIndexEnabled
}
