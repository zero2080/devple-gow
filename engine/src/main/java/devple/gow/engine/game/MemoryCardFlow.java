package devple.gow.engine.game;

import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.ui.MessageTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * 메모리카드 화면 상태기 (원작 MemCard_ReadFlow 0x230248 / MemCard_WriteFlow 0x230A98 의 UI 쪽, 22 문서 §7).
 * <p>
 * FLP(FLP_ShellK / FLP_HUDK 의 SaveLoadMenu) 는 엔진이 {@code PS2_MemoryCard_Event = n} 으로 화면 n(MemoryScreen1..27)을 띄우면
 * 그 화면을 보여 주고, 선택 결과를 {@code FSCommand:PS2_MemoryCard_Select arg} 로 돌려준다 (△ = 0, 예/아니오 = 1/0,
 * 카드 슬롯 1/2, 파일 1..4). 0 을 보내면 메뉴가 닫힌다. 이 클래스가 원작 실행 파일 쪽 순서를 맡는다.
 * <pre>
 * 불러오기: 21 카드 선택 → 23 파일 선택 → 10 불러올까? → 11 불러오는 중 → 13 완료 → 0
 * 저장하기: 21 카드 선택 → 22 파일 선택 → 15 덮어쓸까? / 16 새로 만들까? → 17 저장 중 → 18 완료(○) → 0
 * 오류:     2 카드 없음(○ → 21), 14 GOW 파일 없음(○ → 21), 12 불러오기 오류(○ → 23), 19 저장 오류(○ → 22)
 * </pre>
 * 카드 1 = 세이브 디렉터리 (파일 4개 = slot0..3.save), 카드 2 = 없음.
 * 이동 키는 FLP 가 처리: 카드 슬롯·파일 ←/→ (SaveLoadLeft/Right), 예/아니오 ↑/↓ (UpDownMemoryCard) (기본 = 아니오). 21/22/23 에서 고르면 FLP 가 입력을 잠그고
 * (EnableButton=2) 다음 화면을 기다리므로, 선택을 무시할 때도 화면을 다시 보낸다.
 */
public final class MemoryCardFlow {

	public enum Mode { LOAD, SAVE }

	public enum Result { RUNNING, LOADED, SAVED, CANCELLED }

	public static final int FILES = 4;
	/** 진행 화면(불러오는 중 / 저장 중 / 완료) 최소 표시 시간 (초). */
	static final float BUSY_SEC = 1.0f;

	static final int NO_CARD = 2, LOAD_ASK = 10, LOADING = 11, LOAD_ERROR = 12, LOAD_DONE = 13, NO_FILES = 14,
			OVERWRITE_ASK = 15, CREATE_ASK = 16, SAVING = 17, SAVE_DONE = 18, SAVE_ERROR = 19, CARD_SELECT = 21,
			SAVE_SELECT = 22, LOAD_SELECT = 23;

	private final UiVariables vars;
	private final MessageTable messages;
	private final Path cardDir;
	private final Mode mode;
	private final Supplier<SaveGame> toSave;
	private int state;
	private int file;
	private float timer;
	private Result result = Result.RUNNING;
	private SaveGame loaded;
	/**
	 * 클리어 저장 (원작 0x230A48(0): 0x2A275C = 0 — 끝 종류 ≠ 1 의 상태 17) [확실: 0x230ADC – 0x230B80, 0x230E88 – 0x230ED4, 0x230FA0 – 0x230FE4]:
	 * 단계 0 · 1 이 카드를 살피고 카드가 하나면 (엔진: 늘 슬롯 1) 카드 선택 화면 없이 단계 0xE. 해금 기록은 <b>카드</b> 의 GOW 디렉터리 레코드 (+0x40) 라
	 * 파일을 고르지 않는다: 디렉터리가 있으면 곧 단계 0x19 (레코드 |= 해금 비트, 디렉터리 쓰기 0x22F410) → 0x1A (대기) → MemoryCard_Event 18 "저장하기
	 * 완료", 없으면 새로 만들기 질문 (16, 단계 0xF) → 만들기 (17, 0x11 → 0x22F6B8) → 0x19. 슬롯 항목 (0x18) 과 게임 데이터는 쓰지 않는다.
	 * 영상 5화 10592 질문 "예" → 10595 Ω 만 → 10596 "저장하기 완료" (카드 · 파일 선택 없음).
	 */
	private boolean unlockOnly;

	public MemoryCardFlow(UiVariables vars, MessageTable messages, Path cardDir, Mode mode, Supplier<SaveGame> toSave) {
		this.vars = vars;
		this.messages = messages;
		this.cardDir = cardDir;
		this.mode = mode;
		this.toSave = toSave;
	}

	public static MemoryCardFlow load(UiVariables vars, MessageTable messages, Path cardDir) {
		return new MemoryCardFlow(vars, messages, cardDir, Mode.LOAD, null);
	}

	public static MemoryCardFlow save(UiVariables vars, MessageTable messages, Path cardDir, Supplier<SaveGame> toSave) {
		return new MemoryCardFlow(vars, messages, cardDir, Mode.SAVE, toSave);
	}

	public static Path file(Path cardDir, int n) {
		return cardDir.resolve("slot" + n + ".save");
	}

	/** 클리어 저장 모드 ({@link #unlockOnly}). */
	public MemoryCardFlow unlockOnly() {
		unlockOnly = true;
		return this;
	}

	public void start() {
		if (unlockOnly) {
			if (hasGowDirectory()) {
				unlockWrite();
			} else {
				show(CREATE_ASK);
			}
			return;
		}
		show(CARD_SELECT);
	}

	/** 카드의 GOW 디렉터리 (세이브 파일이나 해금 기록이 하나라도). */
	private boolean hasGowDirectory() {
		if (Files.exists(Unlocks.file(cardDir))) {
			return true;
		}
		for (int i = 0; i < FILES; i++) {
			if (Files.exists(file(cardDir, i))) {
				return true;
			}
		}
		return false;
	}

	/** 단계 0x19 – 0x1A: 화면 바꿈 없이 (Ω 배경만) 해금 쓰기 대기 → 완료 18. */
	private void unlockWrite() {
		state = SAVING;
		timer = BUSY_SEC;
	}

	public Result result() {
		return result;
	}

	public int state() {
		return state;
	}

	/** 지금 화면이 예/아니오 질문 (불러올까? 10 · 덮어쓸까? 15 · 새로 만들까? 16 — FLP 기본 선택 아니오) 인지. */
	public boolean yesNoScreen() {
		return state == LOAD_ASK || state == OVERWRITE_ASK || state == CREATE_ASK;
	}

	/** 불러온 세이브 ({@link Result#LOADED} 일 때). */
	public SaveGame loaded() {
		return loaded;
	}

	/** 불러온/저장한 파일 번호 (0..3). */
	public int fileIndex() {
		return file;
	}

	/** FlpPlayer 명령 리스너에 연결. */
	public void command(String cmd, String arg) {
		if (!"PS2_MemoryCard_Select".equals(cmd) || result != Result.RUNNING) {
			return;
		}
		int a;
		try {
			a = Integer.parseInt(arg.trim());
		} catch (RuntimeException ex) {
			return;
		}
		select(a);
	}

	void select(int a) {
		switch (state) {
			case CARD_SELECT -> {
				if (a == 0) {
					finish(Result.CANCELLED);
				} else if (a != 1) {
					show(NO_CARD);
				} else {
					vars.set("PS2_MemoryCard_CardSlot", a);
					boolean any = fillSlots();
					show(mode == Mode.SAVE ? SAVE_SELECT : any ? LOAD_SELECT : NO_FILES);
				}
			}
			case NO_CARD, NO_FILES -> show(CARD_SELECT);
			case LOAD_SELECT -> {
				if (a == 0) {
					show(CARD_SELECT);
				} else if (a <= FILES && Files.exists(file(cardDir, a - 1))) {
					file = a - 1;
					show(LOAD_ASK);
				} else {
					show(LOAD_SELECT); // 빈 파일: 같은 화면을 다시 띄워 입력 잠금(EnableButton=2)을 푼다
				}
			}
			case SAVE_SELECT -> {
				if (a == 0) {
					show(CARD_SELECT);
				} else if (a <= FILES) {
					file = a - 1;
					show(Files.exists(file(cardDir, file)) ? OVERWRITE_ASK : CREATE_ASK);
				}
			}
			case LOAD_ASK -> busy(a == 1 ? LOADING : LOAD_SELECT);
			case OVERWRITE_ASK, CREATE_ASK -> {
				if (unlockOnly) {
					if (a == 1) {
						busy(SAVING); // 단계 0x10 → 0x11 "만드는 중" (17) → 0x19
					} else {
						finish(Result.CANCELLED); // 흐름 −1 → 상태 17 이 클리어 질문으로 되돌림
					}
				} else {
					busy(a == 1 ? SAVING : SAVE_SELECT);
				}
			}
			case LOAD_ERROR -> show(LOAD_SELECT);
			case SAVE_DONE -> finish(Result.SAVED);
			case SAVE_ERROR -> {
				fillSlots();
				show(SAVE_SELECT);
			}
			default -> {
			}
		}
	}

	/** 매 프레임: 진행 화면 시간 경과 → 실제 읽기/쓰기 → 다음 화면. */
	public void update(float dt) {
		if (result != Result.RUNNING || timer <= 0 || (timer -= dt) > 0) {
			return;
		}
		switch (state) {
			case LOADING -> {
				try {
					loaded = SaveGame.read(file(cardDir, file));
					busy(LOAD_DONE);
				} catch (IOException | RuntimeException ex) {
					System.out.println("불러오기 실패: " + ex);
					show(LOAD_ERROR);
				}
			}
			case LOAD_DONE -> finish(Result.LOADED);
			case SAVING -> {
				try {
					if (!unlockOnly) {
						toSave.get().write(file(cardDir, file)); // 단계 0x12 – 0x18
					}
					Unlocks.persist(cardDir); // 단계 0x19 0x2312DC: 카드 레코드 +0x40 |= g_unlockMask (세이브 포인트 저장도)
					show(SAVE_DONE);
				} catch (IOException | RuntimeException ex) {
					System.out.println("저장 실패: " + ex);
					show(SAVE_ERROR);
				}
			}
			default -> {
			}
		}
	}

	private void busy(int s) {
		show(s);
		if (s == LOADING || s == LOAD_DONE || s == SAVING) {
			timer = BUSY_SEC;
		}
	}

	private void show(int s) {
		state = s;
		timer = 0;
		vars.event("PS2_MemoryCard_Event", s);
	}

	private void finish(Result r) {
		result = r;
		state = 0;
		vars.event("PS2_MemoryCard_Event", 0);
	}

	/**
	 * 파일 목록 변수 — 원작 Save_SlotText 0x22E810 [확실]: 파일마다 HUD +0xE0 (MemoryCard_Name) = 세이브 포인트 이름 (메시지 600 + 번호 첫 줄,
	 * 번호 ≤ 0 이면 "* Not A Save Point *", 메시지가 없으면 "Save Point %d", 빈 파일 = 메시지 2), +0xF0 (MemoryCard_Date) = 파일 시각
	 * ({@link #slotDate}), +0x100 (MemoryCard_PlayTime) = "%s    %d:%02d:%02d" (난이도 이름 0x29DB68[난이도] = 메시지 3..6 + 플레이 시간).
	 * 예전 엔진은 이름 자리에 난이도를, 시간 자리에 시간만 두었다 (영상 5182 슬롯 "아틀라스의 시험" · "전사(보통) 3:10:43", 43 §34).
	 * 그림 = {@link #thumbnails} (0x1B81C0). @return 파일이 하나라도 있으면 true.
	 */
	private boolean fillSlots() {
		boolean any = false;
		int[] points = new int[FILES];
		for (int i = 0; i < FILES; i++) {
			Path f = file(cardDir, i);
			String name = msg(2, "비어있음"), date = "", time = "";
			if (Files.exists(f)) {
				try {
					SaveGame g = SaveGame.read(f);
					points[i] = g.savePoint;
					name = slotName(messages, g.savePoint);
					date = slotDate(LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault()));
					time = msg(3 + Math.max(0, Math.min(3, g.difficulty)), "난이도 " + g.difficulty) + "    " + playTime(g.playTime);
					any = true;
				} catch (IOException | RuntimeException ex) {
					name = "?";
				}
			}
			vars.set("PS2_MemoryCard_Name" + (i + 1), name);
			vars.set("PS2_MemoryCard_Date" + (i + 1), date);
			vars.set("PS2_MemoryCard_PlayTime" + (i + 1), time);
		}
		if (thumbnails != null) {
			thumbnails.accept(points);
		}
		return any;
	}

	/**
	 * 슬롯 그림 (원작 MemCard_Save · MemCard_Load → 0x1B81C0 [확실]: 파일마다 세이브 포인트 번호 &gt; 0 이면 R_SAVEPT 의 "TXR_SavePt%02d", 아니면
	 * MemoryCard_Image1 의 원래 그림 (+0x14A8) 을 MemoryCard_Image1..4 머티리얼에 끼운다 (0x1B8178). R_SAVEPT 는 영웅 WAD 와 함께 게임 중에만 올라
	 * (Hero_LoadWads → 0x1B81B8 +0x14A4) 셸 불러오기 화면은 기본 그림 그대로다. null = 그림 없음 (셸).
	 */
	private java.util.function.Consumer<int[]> thumbnails;

	public MemoryCardFlow thumbnails(java.util.function.Consumer<int[]> c) {
		thumbnails = c;
		return this;
	}

	/** 슬롯 이름 (Save_SlotText 0x22E8C0–0x22EA10): 메시지 600 + 번호 (0x1B95D0 첫 줄, 0x28 자 버퍼). */
	public static String slotName(MessageTable messages, int savePoint) {
		if (savePoint <= 0) {
			return "* Not A Save Point *";
		}
		String m = messages != null ? messages.get(600 + savePoint) : null;
		if (m == null || m.isEmpty()) {
			return "Save Point " + savePoint;
		}
		String line = m.split("\n", 2)[0];
		return line.length() > 0x27 ? line.substring(0, 0x27) : line;
	}

	/**
	 * 슬롯 시각 (원작 0x22E4A8: 카드 디렉터리 시각 → 0x284208 지역 시각, 언어 0x29DB5C = 5 (0x1BF41C 고정) 의 형식
	 * "%d/%d/%2d %2d:%02d:%02d%s" — 년/월/일, 12 시간제 (0 시 → 12, 13 시 이상 −12), " am"/" pm") [확실]. 예: 2023/1/ 9  6:42:53 am (영상 5182).
	 */
	public static String slotDate(LocalDateTime t) {
		int h = t.getHour();
		int h12 = h == 0 ? 12 : h > 12 ? h - 12 : h;
		return String.format(java.util.Locale.ROOT, "%d/%d/%2d %2d:%02d:%02d%s", t.getYear(), t.getMonthValue(), t.getDayOfMonth(), h12, t.getMinute(),
				t.getSecond(), h < 12 ? " am" : " pm");
	}

	static String playTime(float sec) {
		int s = (int) Math.max(0, sec);
		return String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
	}

	private String msg(int id, String def) {
		String m = messages != null ? messages.get(id) : null;
		return m != null && !m.isEmpty() ? m : def;
	}
}
