package soedomoto.protoc.maven;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import com.github.os72.protocjar.PlatformDetector;
import com.github.os72.protocjar.Protoc;
import com.github.os72.protocjar.ProtocVersion;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.COMPILE)
public class ProtocMojo extends AbstractMojo {
    private static final String DEFAULT_INPUT_DIR = "/src/main/protobuf/".replace('/', File.separatorChar);
    @Component
    protected MavenProjectHelper projectHelper;
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;
    @Parameter(property = "extension", defaultValue = "proto")
    String extension;
    @Parameter(property = "protocCommand")
    String protocCommand;
    @Parameter(property = "protocVersion")
    String protocVersion;
    @Parameter(property = "protocArtifact")
    String protocArtifact;
    @Parameter(property = "outputTargets")
    OutputTarget[] outputTargets;
    @Parameter(property = "optimizeCodegen", defaultValue = "true")
    boolean optimizeCodegen;
    @Parameter(property = "includeStdTypes", defaultValue = "true")
    boolean includeStdTypes;
    @Parameter(property = "includeMavenTypes", defaultValue = "none")
    String includeMavenTypes;
    @Parameter(property = "includeImports", defaultValue = "true")
    boolean includeImports;
    @Parameter(property = "compileMavenTypes", defaultValue = "none")
    String compileMavenTypes;
    @Parameter(property = "addProtoSources", defaultValue = "none")
    String addProtoSources;
    @Parameter(property = "includeDirectories")
    File[] includeDirectories;
    @Parameter(property = "inputDirectories")
    File[] inputDirectories;
    @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
    private ArtifactRepository localRepository;
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List<ArtifactRepository> remoteRepositories;
    @Component
    private BuildContext buildContext;
    @Component
    private ArtifactFactory artifactFactory;
    @Component
    private ArtifactResolver artifactResolver;
    private File tempRoot = null;

    static void deleteOnExitRecursive(File dir) {
        dir.deleteOnExit();
        for (File f : dir.listFiles()) {
            f.deleteOnExit();
            if (f.isDirectory()) deleteOnExitRecursive(f);
        }
    }

    static String[] parseArtifactSpec(String artifactSpec, String platform) {
        String[] as = artifactSpec.split(":");
        String[] ret = Arrays.copyOf(as, 5);
        if (ret[3] == null) ret[3] = "exe";
        if (ret[4] == null) ret[4] = platform;
        return ret;
    }

    static File copyFile(File srcFile, File destFile) throws IOException {
        FileInputStream is = null;
        FileOutputStream os = null;
        try {
            is = new FileInputStream(srcFile);
            os = new FileOutputStream(destFile);
            streamCopy(is, os);
        } finally {
            if (is != null) is.close();
            if (os != null) os.close();
        }
        return destFile;
    }

    static void streamCopy(InputStream in, OutputStream out) throws IOException {
        int read = 0;
        byte[] buf = new byte[4096];
        while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
    }

    static File createTempDir(String name) throws MojoExecutionException {
        try {
            File tmpDir = File.createTempFile(name, "");
            tmpDir.delete();
            tmpDir.mkdirs();
            tmpDir.deleteOnExit();
            return tmpDir;
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating temporary directory: " + name, e);
        }
    }

    static File[] addDir(File[] dirs, File dir) {
        if (dirs == null) {
            dirs = new File[]{dir};
        } else {
            dirs = Arrays.copyOf(dirs, dirs.length + 1);
            dirs[dirs.length - 1] = dir;
        }
        return dirs;
    }

    static boolean isEmpty(String s) {
        if (s != null && s.length() > 0) return false;
        return true;
    }

    static long minFileTime(OutputTarget[] outputTargets) {
        long minTime = Long.MAX_VALUE;
        for (OutputTarget target : outputTargets) minTime = Math.min(minTime, minFileTime(target.outputDirectory));
        return minTime;
    }

    static long maxFileTime(File[] dirs) {
        long maxTime = Long.MIN_VALUE;
        for (File dir : dirs) maxTime = Math.max(maxTime, maxFileTime(dir));
        return maxTime;
    }

    static long minFileTime(File current) {
        if (!current.isDirectory()) return current.lastModified();
        long minTime = Long.MAX_VALUE;
        for (File entry : current.listFiles()) minTime = Math.min(minTime, minFileTime(entry));
        return minTime;
    }

    static long maxFileTime(File current) {
        if (!current.isDirectory()) return current.lastModified();
        long maxTime = Long.MIN_VALUE;
        for (File entry : current.listFiles()) maxTime = Math.max(maxTime, maxFileTime(entry));
        return maxTime;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (project.getPackaging() != null && "pom".equals(project.getPackaging().toLowerCase())) {
            getLog().info("Skipping 'pom' packaged project");
            return;
        }

        if (outputTargets == null) {
            outputTargets = new OutputTarget[]{};
        }

        boolean missingOutputDirectory = false;

        for (OutputTarget target : outputTargets) {
            target.addSources = target.addSources.toLowerCase().trim();
            if ("true".equals(target.addSources)) target.addSources = "main";

            if (target.outputDirectory == null) {
                String subdir = "generated-" + ("test".equals(target.addSources) ? "test-" : "") + "sources";
                target.outputDirectory = new File(project.getBuild().getDirectory() + File.separator + subdir + File.separator);
            }

            if (target.outputDirectorySuffix != null) {
                target.outputDirectory = new File(target.outputDirectory, target.outputDirectorySuffix);
            }

            String[] outputFiles = target.outputDirectory.list();
            if (outputFiles == null || outputFiles.length == 0) {
                missingOutputDirectory = true;
            }
        }

        if (!optimizeCodegen) {
            performProtoCompilation(true);
            return;
        }

        File successFile = new File(project.getBuild().getDirectory(), "pjmp-success.txt");
        try {
            long oldestOutputFileTime = minFileTime(outputTargets);
            long newestInputFileTime = maxFileTime(inputDirectories);
            if (successFile.exists() && newestInputFileTime < oldestOutputFileTime && !missingOutputDirectory) {
                getLog().info("Skipping code generation, proto files appear unchanged since last compilation");
                performProtoCompilation(false);
                return;
            }
            successFile.delete();
            performProtoCompilation(true);
            successFile.getParentFile().mkdirs();
            successFile.createNewFile();
        } catch (IOException e) {
            throw new MojoExecutionException("File operation failed: " + successFile, e);
        }
    }

    private void performProtoCompilation(boolean doCodegen) throws MojoExecutionException {
        if (isEmpty(protocVersion)) protocVersion = ProtocVersion.PROTOC_VERSION.mVersion;
        getLog().info("Protoc version: " + protocVersion);

        if (doCodegen) prepareProtoc();

        // even if doCodegen == false, we still extract extra includes/inputs because addProtoSources might be requested
        // this could be optimized further
        File tmpDir = createTempDir("protocjar");

        // extract additional include types
        if (includeStdTypes || hasIncludeMavenTypes()) {
            try {
                File extraTypeDir = new File(tmpDir, "include");
                extraTypeDir.mkdir();
                getLog().info("Additional include types: " + extraTypeDir);
                addIncludeDir(extraTypeDir);
                if (includeStdTypes)
                    Protoc.extractStdTypes(ProtocVersion.getVersion("-v" + protocVersion), tmpDir); // yes, tmpDir
                if (hasIncludeMavenTypes())
                    extractProtosFromDependencies(extraTypeDir, includeMavenTypes.equalsIgnoreCase("transitive"));
                deleteOnExitRecursive(extraTypeDir);
            } catch (Exception e) {
                throw new MojoExecutionException("Error extracting additional include types", e);
            }
        }

        if (inputDirectories == null || inputDirectories.length == 0) {
            File inputDir = new File(project.getBasedir().getAbsolutePath() + DEFAULT_INPUT_DIR);
            inputDirectories = new File[]{inputDir};
        }

        if (hasCompileMavenTypes()) {
            try {
                File mavenTypesCompileDir = new File(tmpDir, "mvncompile");
                mavenTypesCompileDir.mkdir();
                getLog().info("Files to compile from Maven dependencies (" + compileMavenTypes + "): " + mavenTypesCompileDir);
                addInputDir(mavenTypesCompileDir);
                extractProtosFromDependencies(mavenTypesCompileDir, compileMavenTypes.equalsIgnoreCase("transitive"));
                deleteOnExitRecursive(mavenTypesCompileDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Error extracting files from Maven dependencies", e);
            }
        }

        getLog().info("Input directories:");
        for (File input : inputDirectories) {
            getLog().info("    " + input);
            if ("all".equalsIgnoreCase(addProtoSources) || "inputs".equalsIgnoreCase(addProtoSources)) {
                List<String> incs = Arrays.asList("**/*." + extension);
                List<String> excs = new ArrayList<String>();
                projectHelper.addResource(project, input.getAbsolutePath(), incs, excs);
            }
        }

        if (includeDirectories != null && includeDirectories.length > 0) {
            getLog().info("Include directories:");
            for (File include : includeDirectories) {
                getLog().info("    " + include);
                if ("all".equalsIgnoreCase(addProtoSources)) {
                    List<String> incs = Arrays.asList("**/*." + extension);
                    List<String> excs = new ArrayList<String>();
                    projectHelper.addResource(project, include.getAbsolutePath(), incs, excs);
                }
            }
        }

        if (doCodegen) {
            getLog().info("Output targets:");
            for (OutputTarget target : outputTargets) getLog().info("    " + target);
            for (OutputTarget target : outputTargets) preprocessTarget(target);
            for (OutputTarget target : outputTargets) processTarget(target);
        }

        for (OutputTarget target : outputTargets) addGeneratedSources(target);
    }

    private void prepareProtoc() throws MojoExecutionException {
        if (protocCommand != null) {
            try {
                Protoc.runProtoc(protocCommand, new String[]{"--version"});
            } catch (Exception e) {
                protocCommand = null;
            }
        }

        if (protocCommand == null && protocArtifact == null) {
            try {
                // option (1) - extract embedded protoc
                if (protocCommand == null && protocArtifact == null) {
                    File protocFile = Protoc.extractProtoc(ProtocVersion.getVersion("-v" + protocVersion), false);
                    protocCommand = protocFile.getAbsolutePath();
                    try {
                        // some linuxes don't allow exec in /tmp, try one dummy execution, switch to user home if it fails
                        Protoc.runProtoc(protocCommand, new String[]{"--version"});
                    } catch (Exception e) {
                        tempRoot = new File(System.getProperty("user.home"));
                        protocFile = Protoc.extractProtoc(ProtocVersion.getVersion("-v" + protocVersion), false, tempRoot);
                        protocCommand = protocFile.getAbsolutePath();
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Error extracting protoc for version " + protocVersion, e);
            }
        }

        // option (2) - resolve protoc maven artifact (download)
        if (protocCommand == null && protocArtifact != null) {
            protocVersion = ProtocVersion.getVersion("-v:" + protocArtifact).mVersion;
            protocCommand = resolveArtifact(protocArtifact, null).getAbsolutePath();
            try {
                // some linuxes don't allow exec in /tmp, try one dummy execution, switch to user home if it fails
                Protoc.runProtoc(protocCommand, new String[]{"--version"});
            } catch (Exception e) {
                tempRoot = new File(System.getProperty("user.home"));
                protocCommand = resolveArtifact(protocArtifact, tempRoot).getAbsolutePath();
            }
        }

        getLog().info("Protoc command: " + protocCommand);
    }

    private void preprocessTarget(OutputTarget target) throws MojoExecutionException {
        if (!isEmpty(target.pluginArtifact)) {
            target.pluginPath = resolveArtifact(target.pluginArtifact, tempRoot).getAbsolutePath();
        }

        File f = target.outputDirectory;
        if (!f.exists()) {
            getLog().info(f + " does not exist. Creating...");
            f.mkdirs();
        }

        if (target.cleanOutputFolder) {
            try {
                getLog().info("Cleaning " + f);
                FileUtils.cleanDirectory(f);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void processTarget(OutputTarget target) throws MojoExecutionException {
        boolean shaded = false;
        String targetType = target.type;
        if (targetType.equals("java-shaded") || targetType.equals("java_shaded")) {
            targetType = "java";
            shaded = true;
        }

        List<String> protoFilesPath = new ArrayList<>();

        for (File input : inputDirectories) {
            if (input == null) continue;
            if (input.exists() && input.isDirectory()) {
                FileUtils.listFiles(input, new String[]{extension}, true).forEach(file -> {
                    if (!protoFilesPath.contains(file.getAbsolutePath())) {
                        protoFilesPath.add(file.getAbsolutePath());
                    }
                });
            } else {
                if (!protoFilesPath.contains(input.getAbsolutePath())) {
                    protoFilesPath.add(input.getAbsolutePath());
                }
            }
        }

        for (String protoFilePath : protoFilesPath) {
            if (target.cleanOutputFolder || buildContext.hasDelta(protoFilePath)) {
                processFile(new File(protoFilePath), protocVersion, targetType, target.pluginPath, target.outputDirectory, target.outputOptions);
            } else {
                getLog().info("Not changed " + protoFilePath);
            }
        }

        if (shaded) {
            try {
                getLog().info("    Shading (version " + protocVersion + "): " + target.outputDirectory);
                Protoc.doShading(target.outputDirectory, protocVersion);
            } catch (IOException e) {
                throw new MojoExecutionException("Error occurred during shading", e);
            }
        }
    }

    private void processFile(File file, String version, String type, String pluginPath, File outputDir, String outputOptions) throws MojoExecutionException {
        getLog().info("    Processing (" + type + "): " + file.getName());

        try {
            buildContext.removeMessages(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            TeeOutputStream outTee = new TeeOutputStream(System.out, out);
            TeeOutputStream errTee = new TeeOutputStream(System.err, err);

            int ret = 0;
            Collection<String> cmd = buildCommand(file, version, type, pluginPath, outputDir, outputOptions);
            if (protocCommand == null) ret = Protoc.runProtoc(cmd.toArray(new String[0]), outTee, errTee);
            else ret = Protoc.runProtoc(protocCommand, Arrays.asList(cmd.toArray(new String[0])), outTee, errTee);

            // add eclipse m2e warnings/errors
            String errStr = err.toString();
            if (!isEmpty(errStr)) {
                int severity = (ret != 0) ? BuildContext.SEVERITY_ERROR : BuildContext.SEVERITY_WARNING;
                String[] lines = errStr.split("\\n", -1);
                for (String line : lines) {
                    int lineNum = 0;
                    int colNum = 0;
                    String msg = line;
                    if (line.contains(file.getName())) {
                        String[] parts = line.split(":", 4);
                        if (parts.length == 4) {
                            try {
                                lineNum = Integer.parseInt(parts[1]);
                                colNum = Integer.parseInt(parts[2]);
                                msg = parts[3];
                            } catch (Exception e) {
                                getLog().warn("Failed to parse protoc warning/error for " + file);
                            }
                        }
                    }
                    buildContext.addMessage(file, lineNum, colNum, msg, severity, null);
                }
            }

            if (ret != 0) throw new MojoExecutionException("protoc-jar failed for " + file + ". Exit code " + ret);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted", e);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to execute protoc-jar for " + file, e);
        }
    }

    private Collection<String> buildCommand(File file, String version, String type, String pluginPath, File outputDir, String outputOptions) throws MojoExecutionException {
        Collection<String> cmd = new ArrayList<String>();
        populateIncludes(cmd);
        cmd.add("-I" + file.getParentFile().getAbsolutePath());
        if ("descriptor".equals(type)) {
            File outFile = new File(outputDir, file.getName());
            cmd.add("--descriptor_set_out=" + FilenameUtils.removeExtension(outFile.toString()) + ".desc");
            if (includeImports) {
                cmd.add("--include_imports");
            }
            if (outputOptions != null) {
                for (String arg : outputOptions.split("\\s+")) cmd.add(arg);
            }
        } else {
            if (outputOptions != null) {
                cmd.add("--" + type + "_out=" + outputOptions + ":" + outputDir);
            } else {
                cmd.add("--" + type + "_out=" + outputDir);
            }

            if (pluginPath != null) {
                getLog().info("    Plugin path: " + pluginPath);
                cmd.add("--plugin=protoc-gen-" + type + "=" + pluginPath);
            }
        }
        cmd.add(file.toString());
        if (version != null) cmd.add("-v" + version);
        return cmd;
    }

    private void populateIncludes(Collection<String> args) throws MojoExecutionException {
        for (File include : includeDirectories) {
            if (!include.exists())
                throw new MojoExecutionException("Include path '" + include.getPath() + "' does not exist");
            if (!include.isDirectory())
                throw new MojoExecutionException("Include path '" + include.getPath() + "' is not a directory");
            args.add("-I" + include.getPath());
        }
    }

    private void addGeneratedSources(OutputTarget target) throws MojoExecutionException {
        boolean mainAddSources = "main".endsWith(target.addSources);
        boolean testAddSources = "test".endsWith(target.addSources);

        if (mainAddSources) {
            getLog().info("Adding generated sources (" + target.type + "): " + target.outputDirectory);
            project.addCompileSourceRoot(target.outputDirectory.getAbsolutePath());
        }
        if (testAddSources) {
            getLog().info("Adding generated test sources (" + target.type + "): " + target.outputDirectory);
            project.addTestCompileSourceRoot(target.outputDirectory.getAbsolutePath());
        }
        if (mainAddSources || testAddSources) {
            buildContext.refresh(target.outputDirectory);
        }
    }

    private boolean hasIncludeMavenTypes() {
        return includeMavenTypes.equalsIgnoreCase("direct") || includeMavenTypes.equalsIgnoreCase("transitive");
    }

    private void addIncludeDir(File dir) {
        includeDirectories = addDir(includeDirectories, dir);
    }

    private void addInputDir(File dir) {
        inputDirectories = addDir(inputDirectories, dir);
    }

    private boolean hasCompileMavenTypes() {
        return compileMavenTypes.equalsIgnoreCase("direct") || compileMavenTypes.equalsIgnoreCase("transitive");
    }

    private void extractProtosFromDependencies(File dir, boolean transitive) throws IOException {
        for (Artifact artifact : getArtifactsForProtoExtraction(transitive)) {
            if (artifact.getFile() == null) continue;
            getLog().debug("  Scanning artifact: " + artifact.getFile());
            InputStream is = null;
            try {
                if (artifact.getFile().isDirectory()) {
                    for (File f : listFilesRecursively(artifact.getFile(), extension, new ArrayList<File>())) {
                        is = new FileInputStream(f);
                        String name = f.getAbsolutePath().replace(artifact.getFile().getAbsolutePath(), "");
                        if (name.startsWith("/")) name = name.substring(1);
                        writeProtoFile(dir, is, name);
                        is.close();
                    }
                } else {
                    ZipInputStream zis = new ZipInputStream(new FileInputStream(artifact.getFile()));
                    is = zis;
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        if (ze.isDirectory() || !ze.getName().toLowerCase().endsWith(extension)) continue;
                        writeProtoFile(dir, zis, ze.getName());
                        zis.closeEntry();
                    }
                }
            } catch (IOException e) {
                getLog().info("  Error scanning artifact: " + artifact.getFile() + ": " + e);
            } finally {
                if (is != null) is.close();
            }
        }
    }

    private List<File> listFilesRecursively(File directory, String ext, List<File> list) {
        for (File f : directory.listFiles()) {
            if (f.isFile() && f.canRead() && f.getName().toLowerCase().endsWith(ext)) list.add(f);
            else if (f.isDirectory() && f.canExecute()) listFilesRecursively(f, ext, list);
        }
        return list;
    }

    private Set<Artifact> getArtifactsForProtoExtraction(boolean transitive) {
        if (transitive) return project.getArtifacts();
        return project.getDependencyArtifacts();
    }

    private void writeProtoFile(File dir, InputStream zis, String name) throws IOException {
        getLog().info("    " + name);
        File protoOut = new File(dir, name);
        protoOut.getParentFile().mkdirs();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(protoOut);
            streamCopy(zis, fos);
        } finally {
            if (fos != null) fos.close();
        }
    }

    private File resolveArtifact(String artifactSpec, File dir) throws MojoExecutionException {
        try {
            Properties detectorProps = new Properties();
            new PlatformDetector().detect(detectorProps, null);
            String platform = detectorProps.getProperty("os.detected.classifier");

            getLog().info("Resolving artifact: " + artifactSpec + ", platform: " + platform);
            String[] as = parseArtifactSpec(artifactSpec, platform);
            Artifact artifact = artifactFactory.createDependencyArtifact(as[0], as[1], VersionRange.createFromVersionSpec(as[2]), as[3], as[4], Artifact.SCOPE_RUNTIME);
            artifactResolver.resolve(artifact, remoteRepositories, localRepository);

            File tempFile = File.createTempFile(as[1], "." + as[3], dir);
            copyFile(artifact.getFile(), tempFile);
            tempFile.setExecutable(true);
            tempFile.deleteOnExit();
            return tempFile;
        } catch (Exception e) {
            throw new MojoExecutionException("Error resolving artifact: " + artifactSpec, e);
        }
    }
}
