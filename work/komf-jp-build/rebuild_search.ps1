$ErrorActionPreference='Stop'
$java=(Get-Item 'work/jdk21-runtime/*/bin/java.exe' | Select-Object -First 1).FullName
$stage=(Resolve-Path 'work/komf-jp-build').Path
$compiler=(@('kotlin-compiler-embeddable-2.4.20-RC2.jar','kotlin-stdlib-2.4.20-RC2.jar','kotlin-script-runtime-2.4.20-RC2.jar','kotlin-reflect-1.6.10.jar','kotlin-daemon-embeddable-2.4.20-RC2.jar','kotlin-build-tools-api-2.4.20-RC2.jar','kotlinx-coroutines-core-jvm-1.8.0.jar','annotations-26.0.2.jar') | ForEach-Object {Join-Path $stage $_}) -join ';'
$out=Join-Path $stage 'compiled-search-patch'
$testOut=Join-Path $stage 'compiled-search-probe'
New-Item -ItemType Directory -Path $out,$testOut -Force | Out-Null
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 -classpath "$stage/komf-2.0.1.jar" -d $out "$stage/BookWalkerMetadataProvider.kt" "$stage/BookWalkerStoreSearch.kt" "$stage/DmmBooksFallback.kt" "$stage/ProvidersModule.kt" "$stage/MetadataService.kt"
if ($LASTEXITCODE) { throw 'Compilation failed' }
$draft=Join-Path $stage 'komf-bookwalker-jp-2.0.1-search.jar'
Copy-Item "$stage/komf-bookwalker-jp-2.0.1.jar" $draft -Force
& (Join-Path (Split-Path $java) 'jar.exe') uf $draft -C $out snd
if ($LASTEXITCODE) { throw 'JAR update failed' }
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 "-Xfriend-paths=$out" -classpath "$out;$draft" -d $testOut "$stage/LocalProviderProbe.kt" "$stage/StoreSearchProbe.kt" "$stage/PublicationStatusProbe.kt"
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
$env:KOMF_BOOKWALKER_JP_DB="$stage/catalog.sqlite"
& $java -cp "$testOut;$draft" LocalProviderProbeKt
if ($LASTEXITCODE) { throw 'Provider probe failed' }
& $java -cp "$testOut;$draft" snd.komf.providers.bookwalker.StoreSearchProbeKt
if ($LASTEXITCODE) { throw 'Store search probe failed' }
& $java -cp "$testOut;$draft" snd.komf.providers.bookwalker.PublicationStatusProbeKt
if ($LASTEXITCODE) { throw 'Publication status probe failed' }
