/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.connect.build.Artifact;
import io.strimzi.api.kafka.model.connect.build.Build;
import io.strimzi.api.kafka.model.connect.build.JarArtifact;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.connect.build.TgzArtifact;
import io.strimzi.api.kafka.model.connect.build.ZipArtifact;
import io.strimzi.operator.common.Util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * This class is used to generate the Dockerfile used by Kafka Connect Build. It takes the API definition with the
 * desired plugins and generates a Dockerfile which pulls and installs them. To generate the Dockerfile, it is using
 * the PrintWriter.
 */
public class KafkaConnectDockerfile {
    private static final String BASE_PLUGIN_PATH = "/opt/kafka/plugins/";
    private static final String ROOT_USER = "root:root";
    private static final String NON_PRIVILEGED_USER = "1001";

    private final String dockerfile;

    /**
     * Broker configuration template constructor
     *
     * @param fromImage     Image which should be used as a base image in the FROM statement
     * @param connectBuild  The Build definition from the API
     */
    public KafkaConnectDockerfile(String fromImage, Build connectBuild) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);

        printHeader(writer); // Print initial comment
        from(writer, fromImage); // Create FROM statement
        user(writer, ROOT_USER); // Switch to root user to be able to add plugins
        connectorPlugins(writer, connectBuild.getPlugins());
        user(writer, NON_PRIVILEGED_USER); // Switch back to the regular unprivileged user

        dockerfile = stringWriter.toString();

        writer.close();
    }

    /**
     * Generates the FROM statement to the Dockerfile. It uses the image passes in the parameter as the base image.
     *
     * @param writer        Writer for printing the Docker commands
     * @param fromImage     Base image which should be used
     */
    private void from(PrintWriter writer, String fromImage) {
        writer.println("FROM " + fromImage);
        writer.println();
    }

    /**
     * Generates the USER statement in the Dockerfile to switch the user under which the next commands will be running.
     *
     * @param writer    Writer for printing the Docker commands
     * @param user      User to which the Dockefile should switch
     */
    private void user(PrintWriter writer, String user) {
        writer.println("USER " + user);
        writer.println();
    }

    /**
     * Adds the commands to donwload and possibly unpact the connector plugins
     *
     * @param writer    Writer for printing the Docker commands
     * @param plugins   List of plugins which should be added to the container image
     */
    private void connectorPlugins(PrintWriter writer, List<Plugin> plugins) {
        for (Plugin plugin : plugins)   {
            addPlugin(writer, plugin);
        }
    }

    /**
     * Adds a particular connector plugin to the container image. It will go through the individual artifacts and add
     * them one by one depending on their type.
     *
     * @param writer    Writer for printing the Docker commands
     * @param plugin    A single plugin which should be added to the new container image
     */
    private void addPlugin(PrintWriter writer, Plugin plugin)    {
        printSectionHeader(writer, "Connector plugin " + plugin.getName());

        String connectorPath = BASE_PLUGIN_PATH + plugin.getName();

        for (Artifact art : plugin.getArtifacts())  {
            if (art instanceof JarArtifact) {
                addJarArtifact(writer, connectorPath, (JarArtifact) art);
            } else if (art instanceof TgzArtifact) {
                addTgzArtifact(writer, connectorPath, (TgzArtifact) art);
            } else if (art instanceof ZipArtifact) {
                addZipArtifact(writer, connectorPath, (ZipArtifact) art);
            } else {
                throw new RuntimeException("Unexpected artifact type " + art.getType());
            }
        }
    }

    /**
     * Add command sequence for downloading files and checking their checksums.
     *
     * @param writer            Writer for printing the Docker commands
     * @param connectorPath     Path where the connector to which this artifact belongs should be downloaded
     * @param jar               The JAR-type artifact
     */
    private void addJarArtifact(PrintWriter writer, String connectorPath, JarArtifact jar) {
        String artifactDir = connectorPath + "/" + Util.sha1Prefix(jar.getUrl());
        String artifactPath = artifactDir + "/" + jar.getUrl().substring(jar.getUrl().lastIndexOf("/") + 1);
        String downloadCmd =  "curl -L --output " + artifactPath + " " + jar.getUrl();

        writer.println("RUN mkdir -p " + artifactDir + " \\");

        if (jar.getSha512sum() == null || jar.getSha512sum().isEmpty()) {
            // No checksum => we just download the file
            writer.println("      && " + downloadCmd);
        } else {
            // Checksum exists => we need to check it
            String checksum = jar.getSha512sum() + " " + artifactPath;

            writer.println("      && " + downloadCmd + " \\");
            writer.println("      && echo \"" + checksum + "\" > " + artifactPath + ".sha512 \\");
            writer.println("      && sha512sum --check " + artifactPath + ".sha512 \\");
            writer.println("      && rm -f " + artifactPath + ".sha512");
        }

        writer.println();
    }

    /**
     * Add command sequence for downloading and unpacking TAR.GZ archives and checking their checksums.
     *
     * @param writer            Writer for printing the Docker commands
     * @param connectorPath     Path where the connector to which this artifact belongs should be downloaded
     * @param tgz               The TGZ-type artifact
     */
    private void addTgzArtifact(PrintWriter writer, String connectorPath, TgzArtifact tgz) {
        String artifactDir = connectorPath + "/" + Util.sha1Prefix(tgz.getUrl());
        String archivePath = connectorPath + "/" + tgz.getUrl().substring(tgz.getUrl().lastIndexOf("/") + 1);

        String downloadCmd =  "curl -L --output " + archivePath + " " + tgz.getUrl();
        String unpackCmd =  "tar xvfz " + archivePath + " -C " + artifactDir;
        String deleteCmd =  "rm -vf " + archivePath;

        writer.println("RUN mkdir -p " + artifactDir + " \\");

        if (tgz.getSha512sum() == null || tgz.getSha512sum().isEmpty()) {
            // No checksum => we just download and unpack the file
            writer.println("      && " + downloadCmd + " \\");
            writer.println("      && " + unpackCmd + " \\");
            writer.println("      && " + deleteCmd);
        } else {
            // Checksum exists => we need to check it
            String checksum = tgz.getSha512sum() + " " + archivePath;

            writer.println("      && " + downloadCmd + " \\");
            writer.println("      && echo \"" + checksum + "\" > " + archivePath + ".sha512 \\");
            writer.println("      && sha512sum --check " + archivePath + ".sha512 \\");
            writer.println("      && rm -f " + archivePath + ".sha512 \\");
            writer.println("      && " + unpackCmd + " \\");
            writer.println("      && " + deleteCmd);
        }

        writer.println();
    }

    /**
     * Add command sequence for downloading and unpacking TAR.ZIP archives and checking their checksums.
     *
     * @param writer            Writer for printing the Docker commands
     * @param connectorPath     Path where the connector to which this artifact belongs should be downloaded
     * @param zip               The ZIP-type artifact
     */
    private void addZipArtifact(PrintWriter writer, String connectorPath, ZipArtifact zip) {
        String artifactDir = connectorPath + "/" + Util.sha1Prefix(zip.getUrl());
        String archivePath = connectorPath + "/" + zip.getUrl().substring(zip.getUrl().lastIndexOf("/") + 1);

        String downloadCmd =  "curl -L --output " + archivePath + " " + zip.getUrl();
        String unpackCmd =  "unzip " + archivePath + " -d " + artifactDir;
        String deleteSymLinks = "find " + artifactDir + " -type l | xargs rm -f";
        String deleteCmd =  "rm -vf " + archivePath;

        writer.println("RUN mkdir -p " + artifactDir + " \\");

        if (zip.getSha512sum() == null || zip.getSha512sum().isEmpty()) {
            // No checksum => we just download and unpack the file
            writer.println("      && " + downloadCmd + " \\");
            writer.println("      && " + unpackCmd + " \\");
            writer.println("      && " + deleteSymLinks + " \\");
            writer.println("      && " + deleteCmd);
        } else {
            // Checksum exists => we need to check it
            String checksum = zip.getSha512sum() + " " + archivePath;

            writer.println("      && " + downloadCmd + " \\");
            writer.println("      && echo \"" + checksum + "\" > " + archivePath + ".sha512 \\");
            writer.println("      && sha512sum --check " + archivePath + ".sha512 \\");
            writer.println("      && rm -f " + archivePath + ".sha512 \\");
            writer.println("      && " + unpackCmd + " \\");
            writer.println("      && " + deleteSymLinks + " \\");
            writer.println("      && " + deleteCmd);
        }

        writer.println();
    }

    /**
     * Internal method which prints the section header into the Dockerfile. This makes it more human readable.
     *
     * @param sectionName   Name of the section for which is this header printed
     */
    private void printSectionHeader(PrintWriter writer, String sectionName)   {
        writer.println("##########");
        writer.println("# " + sectionName);
        writer.println("##########");
    }

    /**
     * Prints the file header which is on the beginning of the Dockerfile.
     */
    private void printHeader(PrintWriter writer)   {
        writer.println("##############################");
        writer.println("##############################");
        writer.println("# This file is automatically generated by the Strimzi Cluster Operator");
        writer.println("# Any changes to this file will be ignored and overwritten!");
        writer.println("##############################");
        writer.println("##############################");
        writer.println();
    }

    /**
     * Returns the generated Dockerfile for building new Kafka Connect image with additional connectors.
     *
     * @return  Dockerfile
     */
    public String getDockerfile() {
        return dockerfile;
    }

    /**
     * Returns the hash stub identifying the Dockerfile. This can be used to detect changes.
     *
     * @return  Dockerfile hash stub
     */
    public String hashStub()    {
        return Util.sha1Prefix(dockerfile);
    }
}
