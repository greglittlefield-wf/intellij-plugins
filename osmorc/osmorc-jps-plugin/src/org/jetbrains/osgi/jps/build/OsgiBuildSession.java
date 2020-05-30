// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.osgi.jps.build;

import aQute.bnd.osgi.Constants;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.logging.ProjectBuilderLogger;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.DoneSomethingNotification;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.module.JpsDependencyElement;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleDependency;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;
import org.jetbrains.osgi.jps.model.JpsOsmorcExtensionService;
import org.jetbrains.osgi.jps.model.JpsOsmorcModuleExtension;
import org.jetbrains.osgi.jps.model.ManifestGenerationMode;
import org.jetbrains.osgi.jps.model.OsmorcJarContentEntry;
import org.jetbrains.osgi.jps.model.impl.JpsOsmorcModuleExtensionImpl;
import org.jetbrains.osgi.jps.util.OsgiBuildUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.intellij.util.ObjectUtils.coalesce;

public class OsgiBuildSession implements Reporter {
  private static final Logger LOG = Logger.getInstance(OsgiBuildSession.class);

  private OsmorcBuildTarget myTarget;
  private CompileContext myContext;
  private JpsOsmorcModuleExtension myExtension;
  private JpsModule myModule;
  private String myMessagePrefix;
  private File myOutputJarFile;
  private Collection<File> myOutputJarFiles;
  private File myModuleOutputDir;
  private File[] myClasses;
  private File[] mySources;
  private BndWrapper myBndWrapper;
  private String mySourceToReport = null;

  public void build(@NotNull OsmorcBuildTarget target, @NotNull CompileContext context) throws IOException {
    myTarget = target;
    myContext = context;
    myExtension = target.getExtension();
    myModule = target.getModule();
    myMessagePrefix = "[" + myModule.getName() + "] ";

    progress("Building OSGi bundle");

    try {
      prepare();
      doBuild();
    }
    catch (OsgiBuildException e) {
      error(e.getMessage(), e.getCause(), e.getSourcePath(), -1);
      return;
    }

    for (File jarFile : myOutputJarFiles) {
      if (!jarFile.exists()) {
        error("Bundle was not built: " + jarFile, null, null, -1);
        return;
      }
    }

    ProjectBuilderLogger logger = context.getLoggingManager().getProjectBuilderLogger();
    if (logger.isEnabled()) {
      logger.logCompiledFiles(myOutputJarFiles, OsmorcBuilder.ID, "Built OSGi bundles:");
    }

    context.processMessage(DoneSomethingNotification.INSTANCE);
  }

  private void prepare() throws OsgiBuildException {
    myModuleOutputDir = JpsJavaExtensionService.getInstance().getOutputDirectory(myModule, false);
    if (myModuleOutputDir == null) {
      throw new OsgiBuildException("Unable to determine the compiler output path for the module.");
    }

    String jarFileLocation = myExtension.getJarFileLocation();
    if (jarFileLocation.isEmpty()) {
      throw new OsgiBuildException("Bundle path is empty - please check OSGi facet settings.");
    }

    myOutputJarFile = new File(jarFileLocation);
    myOutputJarFiles = myTarget.getOutputRoots(myContext);

    for (File jarFile : myOutputJarFiles) {
      if (!FileUtil.delete(jarFile)) {
        throw new OsgiBuildException("Can't delete bundle file '" + jarFile + "'.");
      }
    }
    if (!FileUtil.createParentDirs(myOutputJarFile)) {
      throw new OsgiBuildException("Cannot create a directory for bundles '" + myOutputJarFile.getParent() + "'.");
    }

    List<File> classes = new SmartList<>();
    if (myModuleOutputDir.exists()) {
      classes.add(myModuleOutputDir);
    }
    for (JpsDependencyElement dependency : myModule.getDependenciesList().getDependencies()) {
      if (dependency instanceof JpsModuleDependency) {
        JpsModule module = ((JpsModuleDependency)dependency).getModule();
        if (module != null && JpsOsmorcExtensionService.getExtension(module) == null) {
          File outputDir = JpsJavaExtensionService.getInstance().getOutputDirectory(module, false);
          if (outputDir != null && outputDir.exists()) {
            classes.add(outputDir);
          }
        }
      }
    }
    myClasses = classes.isEmpty() ? ArrayUtilRt.EMPTY_FILE_ARRAY : classes.toArray(new File[0]);

    List<File> sources = new SmartList<>();
    for (JpsModuleSourceRoot sourceRoot : myModule.getSourceRoots()) {
      File sourceDir = sourceRoot.getFile();
      if (sourceDir.exists()) {
        sources.add(sourceDir);
      }
    }
    mySources = sources.isEmpty() ? ArrayUtilRt.EMPTY_FILE_ARRAY : sources.toArray(new File[0]);

    myBndWrapper = new BndWrapper(this);
  }

  private void doBuild() throws OsgiBuildException {
    progress("Running Bnd to build the bundle");

    if (myExtension.isUseBndFile()) {
      String bndPath = myExtension.getBndFileLocation();
      File bndFile = OsgiBuildUtil.findFileInModuleContentRoots(myModule, bndPath);
      if (bndFile == null || !bndFile.isFile()) {
        throw new OsgiBuildException("Bnd file missing '" + bndPath + "' - please check OSGi facet settings.");
      }

      mySourceToReport = bndFile.getAbsolutePath();
      try {
        myBndWrapper.build(bndFile, myClasses, mySources, myOutputJarFile);
      }
      catch (Exception e) {
        throw new OsgiBuildException("Unexpected build error", e, null);
      }
      mySourceToReport = null;
    }
    else if (myExtension.isUseBundlorFile()) {
      String bundlorPath = myExtension.getBundlorFileLocation();
      File bundlorFile = OsgiBuildUtil.findFileInModuleContentRoots(myModule, bundlorPath);
      if (bundlorFile == null) {
        throw new OsgiBuildException("Bundlor file missing '" + bundlorPath + "' - please check OSGi facet settings.");
      }

      File tempFile = new File(myOutputJarFile.getAbsolutePath() + ".tmp.jar");

      try {
        Map<String, String> properties = Collections.singletonMap(Constants.CREATED_BY, "IntelliJ IDEA / OSGi Plugin");
        myBndWrapper.build(properties, myClasses, mySources, tempFile);
      }
      catch (Exception e) {
        throw new OsgiBuildException("Unexpected build error", e, null);
      }

      progress("Running Bundlor to calculate the manifest");
      try {
        Properties properties = OsgiBuildUtil.getMavenProjectProperties(myContext, myModule);
        List<String> warnings = new BundlorWrapper().wrapModule(properties, tempFile, myOutputJarFile, bundlorFile);
        for (String warning : warnings) {
          warning(warning, null, bundlorFile.getPath(), -1);
        }
      }
      finally {
        if (!FileUtil.delete(tempFile)) {
          warning("Can't delete a temporary file '" + tempFile + "'", null, null, -1);
        }
      }
    }
    else if (myExtension.isManifestManuallyEdited() || myExtension.isOsmorcControlsManifest()) {
      Map<String, String> buildProperties = getBuildProperties();
      if (LOG.isDebugEnabled()) {
        LOG.debug("build properties: " + buildProperties);
      }

      mySourceToReport = getSourceFileToReport();
      try {
        myBndWrapper.build(buildProperties, myClasses, mySources, myOutputJarFile);
      }
      catch (Exception e) {
        throw new OsgiBuildException("Unexpected build error", e, null);
      }
      mySourceToReport = null;
    }
    else {
      ManifestGenerationMode mode = ((JpsOsmorcModuleExtensionImpl)myExtension).getProperties().myManifestGenerationMode;
      throw new OsgiBuildException("Internal error (unknown build method `" + mode + "`)");
    }
  }

  @NotNull
  private Map<String, String> getBuildProperties() throws OsgiBuildException {
    Map<String, String> properties = new HashMap<>();

    // defaults (similar to Maven)

    properties.put(Constants.IMPORT_PACKAGE, "*");
    properties.put(Constants.REMOVEHEADERS, Constants.INCLUDE_RESOURCE + ',' + Constants.PRIVATE_PACKAGE);

    // user settings

    if (myExtension.isOsmorcControlsManifest()) {
      properties.putAll(myExtension.getAdditionalProperties());

      properties.put(Constants.BUNDLE_SYMBOLICNAME, myExtension.getBundleSymbolicName());
      properties.put(Constants.BUNDLE_VERSION, myExtension.getBundleVersion());

      String activator = myExtension.getBundleActivator();
      if (!StringUtil.isEmptyOrSpaces(activator)) {
        properties.put(Constants.BUNDLE_ACTIVATOR, activator);
      }
    }
    else {
      File manifestFile = myExtension.getManifestFile();
      if (manifestFile == null) {
        throw new OsgiBuildException("Manifest file '" + myExtension.getManifestLocation() + "' missing - please check OSGi facet settings.");
      }
      properties.put(Constants.MANIFEST, manifestFile.getAbsolutePath());
    }

    // resources

    List<String> resources = new SmartList<>();

    if (myExtension.isOsmorcControlsManifest()) {
      String custom = properties.get(Constants.INCLUDE_RESOURCE);
      if (custom != null) {
        resources.add(custom);
      }
    }

    for (OsmorcJarContentEntry contentEntry : myExtension.getAdditionalJarContents()) {
      resources.add(contentEntry.myDestination + '=' + contentEntry.mySource);
    }

    if (myExtension.isManifestManuallyEdited()) {
      resources.add(myModuleOutputDir.getPath());
    }

    if (!resources.isEmpty()) {
      properties.put(Constants.INCLUDE_RESOURCE, StringUtil.join(resources, ","));
    }

    String pattern = myExtension.getIgnoreFilePattern();
    if (!StringUtil.isEmptyOrSpaces(pattern)) {
      try {
        Pattern.compile(pattern);
      }
      catch (PatternSyntaxException e) {
        throw new OsgiBuildException("The file ignore pattern is invalid - please check OSGi facet settings.");
      }
      properties.put(Constants.DONOTCOPY, pattern);
    }

    if (myExtension.isOsmorcControlsManifest()) {
      // support the {local-packages} instruction
      progress("Calculating local packages");
      LocalPackageCollector.addLocalPackages(myModuleOutputDir, properties);
    }

    return properties;
  }

  private String getSourceFileToReport() {
    if (myExtension.isManifestManuallyEdited()) {
      File manifestFile = myExtension.getManifestFile();
      if (manifestFile != null) {
        return manifestFile.getPath();
      }
    }
    else {
      File mavenProjectFile = OsgiBuildUtil.getMavenProjectPath(myContext, myModule);
      if (mavenProjectFile != null) {
        return mavenProjectFile.getPath();
      }
    }
    return null;
  }

  @Override
  public void progress(@NotNull String message) {
    myContext.processMessage(new ProgressMessage(myMessagePrefix + message));
  }

  @Override
  public void warning(@NotNull String message, @Nullable Throwable t, @Nullable String sourcePath, int lineNum) {
    process(BuildMessage.Kind.WARNING, message, t, sourcePath, lineNum);
  }

  @Override
  public void error(@NotNull String message, @Nullable Throwable t, @Nullable String sourcePath, int lineNum) {
    process(BuildMessage.Kind.ERROR, message, t, sourcePath, lineNum);
  }

  private void process(BuildMessage.Kind kind, String text, Throwable t, String path, int line) {
    LOG.warn(text, t);
    myContext.processMessage(new CompilerMessage(OsmorcBuilder.ID, kind, myMessagePrefix + text, coalesce(path, mySourceToReport), -1, -1, -1, line, -1));
  }

  @Override
  public boolean isDebugEnabled() {
    return LOG.isDebugEnabled();
  }

  @Override
  public void debug(@NotNull String message) {
    LOG.debug(message);
  }

  @Override
  public String setReportSource(String source) {
    String prevSource = mySourceToReport;
    mySourceToReport = source;
    return prevSource;
  }
}