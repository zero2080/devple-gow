package devple.gow.engine.input;

/** PS2 듀얼쇼크2 버튼. 게임 로직은 이 추상 버튼만 사용한다. */
public enum PadButton {
	CROSS, CIRCLE, SQUARE, TRIANGLE,
	L1, R1, L2, R2, L3, R3,
	START, SELECT,
	UP, DOWN, LEFT, RIGHT;

	/**
	 * 원작 '사용' 버튼 (상자·문·레버·크랭크·밀기·세이브·버튼 안내 1024) = 패드 비트 1 = <b>R2</b> [확실: Pad_Read 0x17C384 가 libpad 버튼 바이트를
	 * (byte0 &lt;&lt; 8 | byte1) ^ 0xFFFF 로 → 비트 0 L2, 1 R2, 2 L1, 3 R1, 4 △, 5 ○, 6 ×, 7 □, 8 SELECT, 9 L3, 10 R3, 11 START, 12–15 방향키;
	 * 버튼 안내 아이콘 R2ButtonStatic; 공개 조작표 "R2 = 액션"]. 마법 = L2 (전투 입력 코드 6 → 비트 0), R1 = 대시 (코드 8 → 비트 3).
	 */
	public static final PadButton USE = R2;
}
