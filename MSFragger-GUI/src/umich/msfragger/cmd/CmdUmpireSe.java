package umich.msfragger.cmd;

import java.awt.Component;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umich.msfragger.exceptions.FileWritingException;
import umich.msfragger.gui.InputLcmsFile;
import umich.msfragger.params.ThisAppProps;
import umich.msfragger.params.umpire.UmpirePanel;
import umich.msfragger.params.umpire.UmpireParams;
import umich.msfragger.params.umpire.UmpireSeGarbageFiles;
import umich.msfragger.util.JarUtils;
import umich.msfragger.util.PropertiesUtils;
import umich.msfragger.util.StringUtils;
import umich.msfragger.util.SwingUtils;
import umich.msfragger.util.UsageTrigger;

public class CmdUmpireSe extends CmdBase {
  private static final Logger log = LoggerFactory.getLogger(CmdUmpireSe.class);
  public static final String NAME = "UmpireSe";
  private static final EXTENSION OUTPUT_EXT = EXTENSION.mzXML;
  public enum EXTENSION {mzXML, mzML}

  public CmdUmpireSe(boolean isRun, Path workDir) {
    super(isRun, workDir);
  }

  @Override
  public String getCmdName() {
    return NAME;
  }

  public List<InputLcmsFile> outputs(List<InputLcmsFile> inputs) {
    if (!isRun)
      return new ArrayList<>(inputs);

    List<InputLcmsFile> out = new ArrayList<>();
    for (InputLcmsFile f: inputs) {
      final String inputFn = f.getPath().getFileName().toString();
      final Path outPath = f.outputDir(wd);
      List<String> mgfs = getGeneratedMgfFnsForMzxml(inputFn);
      List<String> lcmsFns = getGeneratedLcmsFns(mgfs);
      for (String lcmsFn : lcmsFns) {
        out.add(new InputLcmsFile(outPath.resolve(lcmsFn), f.getGroup()));
      }
    }
    return out;
  }

  public boolean configure(Component errMsgParent, boolean isDryRun,
      Path jarFragpipe, UsageTrigger philo, UmpirePanel umpirePanel,
      List<InputLcmsFile> lcmsFiles) {

    pbis.clear();

    // msconvert
    // now all the generated garbage is in the working directory
    //final boolean isWin = OsUtils.isWindows();
    final String binMsconvert = umpirePanel.getBinMsconvert();
    log.debug("Got bin msconvert: {}", binMsconvert);
    if (StringUtils.isNullOrWhitespace(binMsconvert)) {
      JEditorPane message = SwingUtils.createClickableHtml(
          "Specifying path to msconvert binary is required.<br/>\n"
          + "It can be downloaded as part of ProteoWizard:<br/>\n"
          + "<a href='http://proteowizard.sourceforge.net/index.html'>http://proteowizard.sourceforge.net/index.html</a>");
      SwingUtils.makeDialogResizable(message);
      JOptionPane.showMessageDialog(errMsgParent, SwingUtils.wrapInScrollForDialog(message),
          "DIA Umpire SE: Error", JOptionPane.ERROR_MESSAGE);
      return false;
    }

    // check if there are only mzXML input files
    boolean hasNonMzxml = lcmsFiles.stream().map(f -> f.getPath().getFileName().toString().toLowerCase())
        .anyMatch(p -> !p.endsWith(".mzxml"));
    if (hasNonMzxml) {
      JOptionPane.showMessageDialog(errMsgParent,
          "Not all input files are mzXML.\n"
              + "DIA-Umpire only supports mzXML inputs.",
          "DIA Umpire SE: Error", JOptionPane.ERROR_MESSAGE);
      return false;
    }

    // unpack Umpire jar
    Path jarUmpireSe;
    try {
      jarUmpireSe = JarUtils.unpackFromJar(ToolingUtils.class,"/" + UmpireParams.JAR_UMPIRESE_NAME,
          ThisAppProps.UNPACK_TEMP_SUBDIR, true, true);

    } catch (IOException | NullPointerException ex) {
      JOptionPane.showMessageDialog(errMsgParent,
          "Could not unpack UmpireSE jar to a temporary directory.\n",
          "Can't unpack", JOptionPane.ERROR_MESSAGE);
      return false;
    }

    // write umpire params file
    final DateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    final String dateStr = df.format(new Date());
    final UmpireParams collectedUmpireParams = umpirePanel.collect();
    final String umpireParamsFileName =
        UmpireParams.FILE_BASE_NAME + "_" + dateStr + "." + UmpireParams.FILE_BASE_EXT;
    final Path umpireParamsFilePath = wd.resolve(umpireParamsFileName);
    if (!isDryRun) {
      try {
        FileOutputStream fos = new FileOutputStream(umpireParamsFilePath.toFile());
        PropertiesUtils.writePropertiesContent(collectedUmpireParams, fos);
      } catch (FileNotFoundException | FileWritingException e) {
        JOptionPane.showMessageDialog(errMsgParent,
            "[DIA Umpire SE]\nCould not write property file, thus can't run DIA-Umpire",
            "Error", JOptionPane.ERROR_MESSAGE);
        return false;
      }
    }

    // run umpire for each file
    int ramGb = (Integer)umpirePanel.spinnerRam.getValue();
    int ram = ramGb > 0 ? ramGb : 0;

    for (InputLcmsFile f: lcmsFiles) {
      Path inputFn = f.getPath().getFileName();
      Path inputDir = f.getPath().getParent();
      Path destDir = f.outputDir(wd);

      // Umpire-SE
      // java -jar -Xmx8G DIA_Umpire_SE.jar mzMXL_file diaumpire_se.params
      {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        //commands.add("-d64");
        cmd.add("-jar");
        if (ram > 0 && ram < 256)
          cmd.add("-Xmx" + ram + "G");
        cmd.add(jarUmpireSe.toString()); // unpacked UmpireSE jar
        cmd.add(f.getPath().toString());
        cmd.add(umpireParamsFilePath.toString());

        ProcessBuilder pbUmpireSe = new ProcessBuilder(cmd);
        pbis.add(PbiBuilder.from(pbUmpireSe));
      }

      // check if the working dir is the dir where the mzXML file was
      // if it is, then don't do anything, if it is not, then copy
      // UmpireSE outputs to the working directory
      // and also create symlinks to the original files


      if (!inputDir.equals(destDir)) {
        // destination dir is different from mzXML file location
        // need to move output and cleanup
        List<Path> garbage = UmpireSeGarbageFiles.getGarbageFiles(f.getPath());
        List<ProcessBuilder> pbsMove = ToolingUtils.pbsMoveFiles(jarFragpipe, destDir, garbage);
        pbis.addAll(PbiBuilder.from(pbsMove));
      }

      List<String> mgfs = getGeneratedMgfFnsForMzxml(inputFn.toString());
      for (String mgf : mgfs) {
        List<String> cmdMsConvert = new ArrayList<>();

        cmdMsConvert.add(binMsconvert);
        cmdMsConvert.add("--verbose");
        cmdMsConvert.add("--32");
        cmdMsConvert.add("--zlib");
        cmdMsConvert.add("--" + OUTPUT_EXT.toString());
        cmdMsConvert.add("--outdir");
        cmdMsConvert.add(f.outputDir(wd).toString());

//        if (isWin) { // since philosopher 1.5.0 msconvert is not included
//        } else {
//          // on Linux philosopher includes msconvert
//          cmdMsConvert.add(philo.useBin(f.outputDir(wd)));
//          cmdMsConvert.add("msconvert");
//          cmdMsConvert.add("--format");
//          cmdMsConvert.add(OUTPUT_EXT.toString());
//          cmdMsConvert.add("--intencoding");
//          cmdMsConvert.add("32");
//          cmdMsConvert.add("--mzencoding");
//          cmdMsConvert.add("32");
//          cmdMsConvert.add("--zlib");
//        }

        Path mgfPath = f.outputDir(wd).resolve(mgf);
        cmdMsConvert.add(mgfPath.toString());
        ProcessBuilder pbMsConvert = new ProcessBuilder(cmdMsConvert);
        pbMsConvert.directory(mgfPath.getParent().toFile());
        pbMsConvert.environment().putIfAbsent("LC_ALL", "C");
        pbis.add(PbiBuilder.from(pbMsConvert));
      }
    }

    isConfigured = true;
    return true;
  }

  private List<String> getGeneratedMgfFnsForMzxml(String mzxmlFn) {
    String baseName = StringUtils.upToLastDot(mzxmlFn);
    final int n = 3;
    List<String> mgfs = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      mgfs.add(baseName + "_Q" + i + ".mgf");
    }
    return mgfs;
  }

  private List<String> getGeneratedLcmsFns(List<String> generatedMgfFns) {
    return generatedMgfFns.stream()
        .map(mgf -> StringUtils.upToLastDot(mgf) + "." + OUTPUT_EXT.toString())
        .collect(Collectors.toList());
  }

  @Override
  public int getPriority() {
    return 30;
  }
}
