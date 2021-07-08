package soedomoto.protoc.maven;

import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Specifies output target
 */
public class OutputTarget
{
	public OutputTarget() {
		type = "java";
		addSources = "main";
		cleanOutputFolder = false;
		pluginPath = null;
		outputDirectory = null;
		outputOptions = null;
	}

	@Parameter(property = "type", defaultValue = "java")
	String type;

	@Parameter(property = "addSources", defaultValue = "main")
	String addSources;

	@Parameter(property = "cleanOutputFolder", defaultValue = "false")
	boolean cleanOutputFolder;

	@Parameter(property = "pluginPath")
	String pluginPath;

	@Parameter(property = "pluginArtifact")
	String pluginArtifact;

	@Parameter(property = "outputDirectory")
	File outputDirectory;

	@Parameter(property = "outputDirectorySuffix")
	String outputDirectorySuffix;

	@Parameter(property = "outputOptions")
	String outputOptions;

	public String toString() {
		return type + ": " + outputDirectory + " (add: " + addSources + ", clean: " + cleanOutputFolder + ", plugin: " + pluginPath + ", outputOptions: " + outputOptions + ")";
	}
}
