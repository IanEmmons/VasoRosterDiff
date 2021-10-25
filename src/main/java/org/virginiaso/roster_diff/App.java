package org.virginiaso.roster_diff;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class App {
	private final File scilympiadRosterDir;
	private final File masterReportFile;
	private final boolean sendReports;

	public static void main(String[] args) {
		try {
			App app = new App(args);
			app.run();
		} catch (CmdLineException ex) {
			if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
				System.out.format("%n%1$s%n%n", ex.getMessage());
			}
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
	}

	private App(String[] args) throws CmdLineException {
		Properties props = Util.loadPropertiesFromResource(Util.PROPERTIES_RESOURCE);
		scilympiadRosterDir = parseFileArgument(props, "scilympiad.roster.dir");
		masterReportFile = parseFileArgument(props, "master.report.file");
		sendReports = Boolean.parseBoolean(props.getProperty("send.reports", "false"));
	}

	private static File parseFileArgument(Properties props, String propName) throws CmdLineException {
		String fileNameSetting = props.getProperty(propName);
		if (fileNameSetting == null || fileNameSetting.isBlank()) {
			throw new CmdLineException("Configuration setting '%1$s' is missing", propName);
		}
		return new File(fileNameSetting.trim());
	}

	private void run() throws IOException, ParseException {
		List<School> schools = School.getSchools();
		List<Match> matches = Match.parse(masterReportFile);
		List<PortalStudent> pStudents = PortalRosterRetrieverFactory.create().readLatestReportFile();
		List<ScilympiadStudent> sStudents = ScilympiadStudent.readLatestRosterFile(scilympiadRosterDir);

		checkForMissingSchoolsInCoachesFile(schools, pStudents, sStudents);

		System.out.format("Found %1$d portal students and %2$d Scilimpiad students%n",
			pStudents.size(), sStudents.size());

		DifferenceEngine engine = DifferenceEngine.compare(matches, pStudents, sStudents,
			new WeightAvgDistanceFunction());

		//System.out.print(engine.formatDistanceHistogram());
		EnumMap<Verdict, Long> verdictCounts = engine.getMatches().stream()
			.collect(Collectors.groupingBy(
				Match::getVerdict,						// classifier
				() -> new EnumMap<>(Verdict.class),	// map factory
				Collectors.counting()));				// downstream collector
		System.out.format("Marked Same:      %1$3d%n",
			verdictCounts.getOrDefault(Verdict.SAME, 0L));
		System.out.format("Marked Different: %1$3d%n",
			verdictCounts.getOrDefault(Verdict.DIFFERENT, 0L));
		System.out.format("Exact matches:    %1$3d%n",
			verdictCounts.getOrDefault(Verdict.EXACT_MATCH, 0L));
		System.out.format("Portal students not in Scilympiad:     %1$3d%n",
			engine.getPStudentsNotFoundInS().size());
		System.out.format("Scilympiad students not in the Portal: %1$3d%n",
			engine.getSStudentsNotFoundInP().size());

		Stopwatch reportTimer = new Stopwatch();
		ReportBuilder rb = new ReportBuilder(engine, masterReportFile, getReportDir());
		rb.createReport(null, false);

		schools.stream().forEach(school -> rb.createReport(school, sendReports));
		reportTimer.stopAndReport("Built reports");
	}

	private void checkForMissingSchoolsInCoachesFile(List<School> schools,
			List<PortalStudent> pStudents, List<ScilympiadStudent> sStudents) {
		Set<String> schoolNames = new TreeSet<>();
		pStudents.stream()
			.map(pStudent -> pStudent.school)
			.forEach(schoolNames::add);
		sStudents.stream()
			.map(sStudent -> sStudent.school)
			.forEach(schoolNames::add);
		List<String> unknownSchools = schoolNames.stream()
			.filter(schoolName -> schools.stream().noneMatch(
				school -> school.name.equalsIgnoreCase(schoolName)))
			.collect(Collectors.toUnmodifiableList());
		if (!unknownSchools.isEmpty()) {
			System.out.format("Schools not in coach file (%1$d):%n", unknownSchools.size());
			unknownSchools.forEach(name -> System.out.format("   %1$s%n", name));
		}
	}

	private static File getReportDir() {
		File reportDir = new File(String
			.format("reports-%1$TF_%1$TT", System.currentTimeMillis())
			.replace(':', '-'));
		if (reportDir.exists()) {
			throw new IllegalStateException(String.format(
				"Report directory '%1$s' already exists", reportDir.getPath()));
		}
		return reportDir;
	}
}
