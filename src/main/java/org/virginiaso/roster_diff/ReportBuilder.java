package org.virginiaso.roster_diff;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;

public class ReportBuilder {
	private static final String P_NOT_S_SHEET_TITLE = "In Portal, not Scilympiad";
	private static final String S_NOT_P_SHEET_TITLE = "In Scilympiad, not Portal";
	static final String MATCHES_SHEET_TITLE = "Adjudicated Matches";
	static final String SCILYMPIAD_ROW_LABEL = "Scilympiad:";
	static final String PORTAL_ROW_LABEL = "Portal:";
	private static final int VERDICT_COLUMN_NUMBER = 7;
	private static final String[] VERDICT_COLUMN_VALUES = {"—", "Different", "Same"};
	private static final String[] HEADINGS_FOR_STUDENTS_IN_ONLY_ONE_SYSTEM = {
		"School", "Last Name", "First Name", "Nickname", "Grade"
	};
	private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
		.setHeader(HEADINGS_FOR_STUDENTS_IN_ONLY_ONE_SYSTEM)
		.setTrim(true)
		.build();

	private static enum Style {
		WHITE, GRAY,
		WHITE_FIRST_COLUMN, GRAY_FIRST_COLUMN,
		WHITE_VERDICT_COLUMN, GRAY_VERDICT_COLUMN
	}

	private final Emailer emailer;
	private final DifferenceEngine engine;
	private final File masterReport;
	private final File reportDir;

	public ReportBuilder(DifferenceEngine engine, File masterReport, File reportDir)
			throws IOException {
		emailer = new Emailer();
		this.engine = Objects.requireNonNull(engine, "engine");
		this.masterReport = Objects.requireNonNull(masterReport, "masterReportFile");
		this.reportDir = Objects.requireNonNull(reportDir, "reportDir");
	}

	public void createReport(String schoolName, List<Coach> coaches, boolean sendEmail) {
		if (schoolName == null || coaches == null) {
			try (Workbook workbook = new XSSFWorkbook(XSSFWorkbookType.XLSX)) {
				createMatchesSheet(workbook);
				createSNotInPSheet(workbook, null);
				createPNotInSSheet(workbook, null);

				try (OutputStream os = new FileOutputStream(getReportFile(null))) {
					workbook.write(os);
				}
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		} else {
			long numSStudentsNotFoundInP = engine.getSStudentsNotFoundInP().stream()
				.filter(student -> student.school.equals(schoolName))
				.count();
			if (numSStudentsNotFoundInP <= 0) {
				return;
			}

			File report = createSchoolReport(schoolName);
			if (sendEmail) {
				List<String> recipients = coaches.stream()
					.map(Coach::prettyEmail)
					.collect(Collectors.toUnmodifiableList());
				emailer.send(report, recipients);
			}
		}
	}

	private void createMatchesSheet(Workbook workbook) {
		/*
		 * First, we create a new matches data structure that combines the near-matches
		 * found by the difference engine with the manually adjudicated matches from the
		 * master report.
		 */
		Map<ScilympiadStudent, Map<Integer, List<Student>>> matchesForDisplay
			= new TreeMap<>();
		matchesForDisplay.putAll(engine.getResults());
		engine.getMatches().stream()
			.filter(match -> match.getVerdict() != Verdict.EXACT_MATCH)
			.forEach(match -> matchesForDisplay
				.computeIfAbsent(match.getSStudent(), key -> new TreeMap<>())
				.computeIfAbsent(match.getVerdict().getCorrespondingDistance(), key -> new ArrayList<>())
				.add(match.getPStudent()));

		EnumMap<Style, CellStyle> styles = createMatchesSheetStyles(workbook);

		Sheet sheet = workbook.createSheet(MATCHES_SHEET_TITLE);
		setHeadings(sheet, "Source", "Distance", "School", "Last Name", "First Name",
			"Nickname", "Grade", "Verdict");
		boolean isEvenSStudentIndex = false;
		List<Integer> portalRowNumbers = new ArrayList<>();
		for (var entry : matchesForDisplay.entrySet()) {
			ScilympiadStudent sStudent = entry.getKey();
			Map<Integer, List<Student>> matches = entry.getValue();
			createNearMatchRowScilympiad(sheet, sStudent, matches, styles,
				isEvenSStudentIndex, portalRowNumbers);
			isEvenSStudentIndex = !isEvenSStudentIndex;
		}
		setValidation(sheet, portalRowNumbers);
		sheet.createFreezePane(0, 1);
		autoSizeColumns(sheet);
	}

	private EnumMap<Style, CellStyle> createMatchesSheetStyles(Workbook workbook) {
		EnumMap<Style, CellStyle> result = new EnumMap<>(Style.class);

		CellStyle white = workbook.createCellStyle();
		result.put(Style.WHITE, white);

		CellStyle firstColWhite = workbook.createCellStyle();
		firstColWhite.setAlignment(HorizontalAlignment.RIGHT);
		result.put(Style.WHITE_FIRST_COLUMN, firstColWhite);

		CellStyle gray = workbook.createCellStyle();
		gray.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		gray.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		result.put(Style.GRAY, gray);

		CellStyle firstColGray = workbook.createCellStyle();
		firstColGray.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		firstColGray.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		firstColGray.setAlignment(HorizontalAlignment.RIGHT);
		result.put(Style.GRAY_FIRST_COLUMN, firstColGray);

		result.put(Style.WHITE_VERDICT_COLUMN, white);
		result.put(Style.GRAY_VERDICT_COLUMN, gray);

		return result;
	}

	private void createNearMatchRowScilympiad(Sheet sheet, ScilympiadStudent sStudent,
			Map<Integer, List<Student>> matches, EnumMap<Style, CellStyle> styles,
			boolean isEvenSStudentIndex, List<Integer> portalRowNumbers) {
		Row row = createNextRow(sheet);
		CellStyle firstStyle = styles.get(
			isEvenSStudentIndex ? Style.WHITE_FIRST_COLUMN : Style.GRAY_FIRST_COLUMN);
		CellStyle subsequentStyle = styles.get(
			isEvenSStudentIndex ? Style.WHITE : Style.GRAY);
		CellStyle verdictStyle = styles.get(
			isEvenSStudentIndex ? Style.WHITE_VERDICT_COLUMN : Style.GRAY_VERDICT_COLUMN);
		createNextCell(row, CellType.STRING, firstStyle)
			.setCellValue(SCILYMPIAD_ROW_LABEL);
		createNextCell(row, CellType.BLANK, subsequentStyle);
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(sStudent.school);
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(sStudent.lastName);
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(sStudent.firstName);
		createNextCell(row, CellType.BLANK, subsequentStyle);
		createNextCell(row, CellType.NUMERIC, subsequentStyle)
			.setCellValue(sStudent.grade);
		createNextCell(row, CellType.BLANK, verdictStyle);

		matches.entrySet().stream().forEach(
			entry -> entry.getValue().forEach(
				pStudent -> createNearMatchRowPortal(sheet, entry.getKey(), pStudent,
					firstStyle, subsequentStyle, portalRowNumbers)));
	}

	private void createNearMatchRowPortal(Sheet sheet, int distance,
			Student pStudent, CellStyle firstStyle, CellStyle subsequentStyle,
			List<Integer> portalRowNumbers) {
		Row row = createNextRow(sheet);
		portalRowNumbers.add(row.getRowNum());
		createNextCell(row, CellType.STRING, firstStyle)
			.setCellValue(PORTAL_ROW_LABEL);
		if (distance < 0) {
			createNextCell(row, CellType.BLANK, subsequentStyle);
		} else {
			createNextCell(row, CellType.NUMERIC, subsequentStyle)
				.setCellValue(distance);
		}
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(pStudent.school());
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(pStudent.lastName());
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(pStudent.firstName());
		createNextCell(row, CellType.STRING, subsequentStyle)
			.setCellValue(pStudent.nickName());
		createNextCell(row, CellType.NUMERIC, subsequentStyle)
			.setCellValue(pStudent.grade());
		if (distance == Verdict.SAME.getCorrespondingDistance()) {
			createNextCell(row, CellType.STRING, subsequentStyle)
				.setCellValue(VERDICT_COLUMN_VALUES[2]);
		} else if (distance == Verdict.DIFFERENT.getCorrespondingDistance()) {
			createNextCell(row, CellType.STRING, subsequentStyle)
				.setCellValue(VERDICT_COLUMN_VALUES[1]);
		} else {
			createNextCell(row, CellType.STRING, subsequentStyle)
				.setCellValue(VERDICT_COLUMN_VALUES[0]);
		}
	}

	private void setValidation(Sheet sheet, List<Integer> portalRowNumbers) {
		CellRangeAddressList addressList = new CellRangeAddressList();
		for (int rowNumber : portalRowNumbers) {
			addressList.addCellRangeAddress(
				rowNumber, VERDICT_COLUMN_NUMBER, rowNumber, VERDICT_COLUMN_NUMBER);
		}

		DataValidationHelper dvHelper = sheet.getDataValidationHelper();
		DataValidationConstraint dvConstraint
			= dvHelper.createExplicitListConstraint(VERDICT_COLUMN_VALUES);
		DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
		validation.setSuppressDropDownArrow(true);
		validation.setShowErrorBox(true);
		validation.setEmptyCellAllowed(true);
		sheet.addValidationData(validation);
	}

	private void createSNotInPSheet(Workbook workbook, String schoolName) {
		Sheet sheet = workbook.createSheet(S_NOT_P_SHEET_TITLE);
		setHeadings(sheet, HEADINGS_FOR_STUDENTS_IN_ONLY_ONE_SYSTEM);
		engine.getSStudentsNotFoundInP().stream()
			.filter(student -> (schoolName == null || student.school.equals(schoolName)))
			.forEach(student -> createScilympiadStudentRow(sheet, student));
		sheet.createFreezePane(0, 1);
		autoSizeColumns(sheet);
	}

	private void createPNotInSSheet(Workbook workbook, String schoolName) {
		Sheet sheet = workbook.createSheet(P_NOT_S_SHEET_TITLE);
		setHeadings(sheet, HEADINGS_FOR_STUDENTS_IN_ONLY_ONE_SYSTEM);
		engine.getPStudentsNotFoundInS().stream()
			.filter(student -> (schoolName == null || student.school().equals(schoolName)))
			.forEach(student -> createPortalStudentRow(sheet, student));
		sheet.createFreezePane(0, 1);
		autoSizeColumns(sheet);
	}

	private File createSchoolReport(String schoolName) {
		File file = getReportFile(schoolName);
		try (CSVPrinter printer = FORMAT.print(file, Util.CHARSET)) {
			createSectionRow(printer,
				"Scilympiad Students with no Permission in the Portal:");
			engine.getSStudentsNotFoundInP().stream()
				.filter(student -> student.school.equals(schoolName))
				.forEach(student -> createScilympiadStudentRow(printer, student));

			printer.println();
			createSectionRow(printer,
				"Portal Students that do not appear in Scilympiad (just FYI - no action required):");
			engine.getPStudentsNotFoundInS().stream()
				.filter(student -> student.school().equals(schoolName))
				.forEach(student -> createPortalStudentRow(printer, student));

			return file;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void createSectionRow(CSVPrinter printer, String sectionTitle) throws IOException {
		printer.println();
		printer.print(sectionTitle);
		printer.println();
		printer.println();
	}

	private void createScilympiadStudentRow(CSVPrinter printer, ScilympiadStudent student) {
		try {
			printer.print(student.school);
			printer.print(student.lastName);
			printer.print(student.firstName);
			printer.print("");
			printer.print(Integer.toString(student.grade));
			printer.println();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void createScilympiadStudentRow(Sheet sheet, ScilympiadStudent student) {
		Row row = createNextRow(sheet);
		createNextCell(row, CellType.STRING)
			.setCellValue(student.school);
		createNextCell(row, CellType.STRING)
			.setCellValue(student.lastName);
		createNextCell(row, CellType.STRING)
			.setCellValue(student.firstName);
		createNextCell(row, CellType.BLANK);
		createNextCell(row, CellType.NUMERIC)
			.setCellValue(student.grade);
	}

	private void createPortalStudentRow(CSVPrinter printer, Student student) {
		try {
			printer.print(student.school());
			printer.print(student.lastName());
			printer.print(student.firstName());
			printer.print(student.nickName());
			printer.print(Integer.toString(student.grade()));
			printer.println();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void createPortalStudentRow(Sheet sheet, Student student) {
		Row row = createNextRow(sheet);
		createNextCell(row, CellType.STRING)
			.setCellValue(student.school());
		createNextCell(row, CellType.STRING)
			.setCellValue(student.lastName());
		createNextCell(row, CellType.STRING)
			.setCellValue(student.firstName());
		createNextCell(row, CellType.STRING)
			.setCellValue(student.nickName());
		createNextCell(row, CellType.NUMERIC)
			.setCellValue(student.grade());
	}

	private void setHeadings(Sheet sheet, String... headings) {
		Row row = createNextRow(sheet);
		for (String heading : headings) {
			createNextCell(row, CellType.STRING)
				.setCellValue(heading);
		}
	}

	private Row createNextRow(Sheet sheet) {
		// The result from getLastRowNum() does not include the +1:
		int lastRowNum = sheet.getLastRowNum();
		return sheet.createRow(
			(lastRowNum == -1) ? 0 : lastRowNum + 1);
	}

	private Cell createNextCell(Row row, CellType cellType, CellStyle cellStyle) {
		Cell cell = createNextCell(row, cellType);
		cell.setCellStyle(cellStyle);
		return cell;
	}

	private Cell createNextCell(Row row, CellType cellType) {
		// The result from getLastCellNum() already includes the +1:
		int lastCellNum = row.getLastCellNum();
		return row.createCell(
			(lastCellNum == -1) ? 0 : lastCellNum,
			cellType);
	}

	private void autoSizeColumns(Sheet sheet) {
		for (int colNum = 0; colNum < sheet.getRow(0).getLastCellNum(); ++colNum) {
			sheet.autoSizeColumn(colNum);
		}
	}

	private File getReportFile(String schoolName) {
		if (schoolName == null) {
			return masterReport;
		} else {
			reportDir.mkdirs();
			StringBuilder buffer = new StringBuilder();
			schoolName.chars()
				.filter(ch -> ch != '.')
				.map(ch -> (ch == ' ') ? '-' : ch)
				.forEach(ch -> buffer.append((char) ch));
			return new File(reportDir, "%1$s.csv".formatted(buffer));
		}
	}
}
