package org.virginiaso.roster_diff;

import org.apache.commons.text.similarity.LevenshteinDistance;

public class WeightAvgDistanceFunction implements DistanceFunction {
	private static final int LAST_WEIGHT = 1;
	private static final int FIRST_WEIGHT = 1;
	private static final int GRADE_WEIGHT = 3;
	private static final int SCHOOL_WEIGHT = 10;
	private static final LevenshteinDistance LD = LevenshteinDistance.getDefaultInstance();

	@Override
	public int applyAsInt(Student lhsStudent, Student rhsStudent) {
		int lastNameDist = lowerLD(lhsStudent.lastName(), rhsStudent.lastName());
		int firstNameDist = lowerLD(lhsStudent.firstName(), rhsStudent.firstName());
		firstNameDist = Math.min(firstNameDist,
			lowerLD(lhsStudent.nickName(), rhsStudent.firstName()));
		firstNameDist = Math.min(firstNameDist,
			lowerLD(rhsStudent.nickName(), lhsStudent.firstName()));
		int gradeDist = Math.abs(lhsStudent.grade() - rhsStudent.grade());
		int schoolDist = lowerLD(lhsStudent.school(), rhsStudent.school());
		return LAST_WEIGHT * lastNameDist
			+ FIRST_WEIGHT * firstNameDist
			+ GRADE_WEIGHT * gradeDist
			+ SCHOOL_WEIGHT * schoolDist;
	}

	private static int lowerLD(String lhs, String rhs) {
		return LD.apply(lhs.toLowerCase(), rhs.toLowerCase());
	}
}
