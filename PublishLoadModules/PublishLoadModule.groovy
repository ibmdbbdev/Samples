@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import java.io.File
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import org.apache.http.entity.FileEntity
import com.ibm.dbb.build.*
import com.ibm.dbb.build.DBBConstants.CopyMode
import com.ibm.dbb.build.report.BuildReport
import com.ibm.dbb.build.report.records.DefaultRecordFactory

/************************************************************************************
 * This script publishes the outputs generated from a build to an Artifactory
 * repository.
 *
 ************************************************************************************/

def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent

// Load the Tools.groovy utility script
def tools = loadScript(new File("$scriptDir/Tools.groovy"))

// Parse command line arguments and load build properties
def usage = "PublishLoadModule.groovy [options]"
def opts = tools.parseArgs(args, usage)
def properties = tools.loadProperties(opts)

def workDir = properties.workDir
def loadDatasets = properties.loadDatasets

// Retrieve the build report and parse the outputs from the build report
def buildReportFile = new File("$workDir/BuildReport.json")
assert buildReportFile.exists(), "$buildReportFile does not exist"

def buildReport = BuildReport.parse(buildReportFile.newInputStream())
def executes = buildReport.records.findAll { record ->
    record.type == DefaultRecordFactory.TYPE_EXECUTE && !record.outputs.isEmpty()
}

assert executes.size() > 0, "There are no outputs found in the build report"

// If the user specifies the build property 'loadDatasets' then retrieves it
// and filters out only outputs that match with the specified data sets.
def loadDatasetArray  = loadDatasets?.split(",")
def loadDatasetList = loadDatasetArray == null ? [] : Arrays.asList(loadDatasetArray)

def loadDatasetToMembersMap = [:]  
def loadCount = 0
executes.each { execute ->
    execute.outputs.each { output ->
        def (dataset, member) = output.dataset.split("\\(|\\)")        
        if (loadDatasetList.isEmpty() || loadDatasetList.contains(dataset))
        {            
            if (loadDatasetToMembersMap[dataset] == null)
                loadDatasetToMembersMap[dataset] = []
            loadDatasetToMembersMap[dataset].add(member)
            loadCount++        
        }
    }
}

assert loadCount > 0, "There are no load modules to publish"

// Create a temporary directory on zFS to copy the load modules from data sets to
def tempLoadDir = new File("$workDir/tempLoadDir")
!tempLoadDir.exists() ?: tempLoadDir.deleteDir()
tempLoadDir.mkdirs()

// For each load module, use CopyToHFS with respective CopyMode option to maintain SSI
def copy = new CopyToHFS()
def copyModeMap = ["COPYBOOK": CopyMode.TEXT, "DBRM": CopyMode.BINARY, "LOAD": CopyMode.LOAD]
println "Number of load modules to publish: $loadCount"

// Create dedicated directories for datasets (e.g. load modules and DBRMs)
loadDatasetToMembersMap.each { dataset, members ->
    datasetDir = new File("$tempLoadDir/$dataset")
    datasetDir.mkdirs()

    currentCopyMode = copyModeMap[dataset.replaceAll(/.*\.([^.]*)/, "\$1")]
    copy.setCopyMode(currentCopyMode)
    copy.setDataset(dataset)

    members.each { member ->
        println "Copying $dataset($member) to $datasetDir"
        copy.member(member).file(new File("$datasetDir/$member")).copy()
    }

}

// Append build report
def exportBuildReport = new File("$tempLoadDir/BuildReport.json")
exportBuildReport << buildReportFile.text

def date = new Date()
def sdf = new SimpleDateFormat("yyyyMMdd-HHmmss")
def startTime = sdf.format(date) as String

// Package the load files just copied into a tar file using the build
// label as the name for the tar file.
def buildGroup = "${properties.collection}" as String
def buildLabel = "build.$startTime" as String
def tarFile = new File("$tempLoadDir/${buildLabel}.tar")
def tarOut = ["sh", "-c", "cd $tempLoadDir && tar cf $tarFile *"].execute().text

// Set up the artifactory information to publish the tar file
def artifactoryURL = properties.get("artifactory.url") as String
def artifactoryRepo = properties.get("artifactory.repo") as String
def artifactoryKey = properties.get("artifactory.apiKey") as String
def remotePath = "${buildGroup}/${tarFile.name}"

// Call the ArtifactoryHelpers to publish the tar file
File artifactoryHelpersFile = new File("$scriptDir/ArtifactoryHelpers.groovy")
Class artifactoryHelpersClass = new GroovyClassLoader(getClass().getClassLoader()).parseClass(artifactoryHelpersFile)
GroovyObject artifactoryHelpers = (GroovyObject) artifactoryHelpersClass.newInstance()
artifactoryHelpers.publish(artifactoryURL, artifactoryRepo, artifactoryKey, remotePath, tarFile)

