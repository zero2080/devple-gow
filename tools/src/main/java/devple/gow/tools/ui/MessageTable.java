package devple.gow.tools.ui;

import devple.gow.tools.content.Content;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 게임 메시지 테이블 {@code msgsK.txt} (R_PERMK.WAD, 태그 0x6F 의 원시 파일).
 * UTF-8 텍스트, NUL 종료. 형식은 "{@code *ID*\n본문\n}" 의 반복.
 * <ul>
 *   <li>FLP 동적 라벨 변수 {@code PS2_<ID>} 가 이 ID 를 가리킨다 (예: PS2_4051 → "옵션").</li>
 *   <li>본문 안 {@code [CircleButton]} 등은 버튼/아이콘 치환 태그, {@code [*1]} 은 런타임 인자 자리.</li>
 *   <li>{@code ----} 줄은 페이지 구분.</li>
 * </ul>
 * 모드 교체 (49 문서 §8): {@link #fromWad} 가 계층의 {@code text/msgs.txt} (원본 형식 통째 교체) 와
 * {@code text/messages.properties} ({@code ID=본문}, ID 단위 덮어쓰기·새 ID 추가) 를 적용한다.
 * HUD·메뉴·스크립트 ShowMessage·질문 상자가 모두 이 표를 쓴다.
 */
public final class MessageTable {

	/** 메시지 테이블이 들어 있는 원시 파일 태그 ID (MC_ICON 과 같은 0x6F). */
	public static final int RAW_FILE_TAG = 0x6F;
	/** 계층의 원본 형식 통째 교체 파일. */
	public static final String REPLACE_FILE = "text/msgs.txt";
	/** 계층의 ID 단위 덮어쓰기 표 (UTF-8 properties, 줄바꿈은 {@code \n}). */
	public static final String OVERRIDE_FILE = "text/messages.properties";
	private static final Pattern ID_LINE = Pattern.compile("^\\*(\\d+)\\*\\n", Pattern.MULTILINE);

	private final Map<Integer, String> messages = new TreeMap<>();

	public MessageTable(String text) {
		Matcher m = ID_LINE.matcher(text);
		int id = -1, start = 0;
		while (m.find()) {
			if (id >= 0) {
				put(id, text.substring(start, m.start()));
			}
			id = Integer.parseInt(m.group(1));
			start = m.end();
		}
		if (id >= 0) {
			put(id, text.substring(start));
		}
	}

	private void put(int id, String body) {
		messages.put(id, body.endsWith("\n") ? body.substring(0, body.length() - 1) : body);
	}

	/** WAD 안의 메시지 테이블 + 모드 교체 ({@link #withOverrides}). 엔진의 모든 메시지 표가 이 함수를 지난다. */
	public static Optional<MessageTable> fromWad(WadFile wad) {
		return fromWadRaw(wad).map(MessageTable::withOverrides);
	}

	/**
	 * 계층 교체를 적용한 표 (49 문서 §8): 가장 위 계층의 text/msgs.txt 가 있으면 그것으로 통째 바꾸고, 그 위에
	 * text/messages.properties 를 아래 계층부터 ID 단위로 덮어쓴다 (없던 ID 는 추가). 교체가 없으면 this.
	 */
	public MessageTable withOverrides() {
		Optional<Path> full = Content.find(REPLACE_FILE);
		Properties over = Content.properties(OVERRIDE_FILE);
		if (full.isEmpty() && over.isEmpty()) {
			return this;
		}
		MessageTable out = new MessageTable("");
		out.messages.putAll(messages);
		if (full.isPresent()) {
			try {
				out = new MessageTable(Files.readString(full.get(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				System.err.println("[text] " + full.get() + " 읽기 실패: " + e.getMessage());
			}
		}
		for (String k : over.stringPropertyNames()) {
			try {
				out.messages.put(Integer.parseInt(k.trim()), over.getProperty(k));
			} catch (NumberFormatException e) {
				System.err.println("[text] " + OVERRIDE_FILE + ": ID 가 아닌 키 " + k + " — 건너뜀");
			}
		}
		return out;
	}

	/** 덮어쓰기 표 한 줄 ({@code ID=본문}, 줄바꿈 → {@code \n}). properties 로 다시 읽으면 본문이 그대로다. */
	public static String overrideLine(int id, String text) {
		StringBuilder b = new StringBuilder().append(id).append('=');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				case ' ' -> b.append(i == 0 ? "\\ " : " ");
				default -> b.append(c);
			}
		}
		return b.toString();
	}

	/** WAD 안에서 이름이 msgs*.txt 인 원시 파일 태그를 찾아 파싱 (모드 교체 없음 — 원본 내보내기용). */
	public static Optional<MessageTable> fromWadRaw(WadFile wad) {
		for (WadTag t : wad.tags()) {
			if (t.tag() == RAW_FILE_TAG && t.name().toLowerCase().endsWith(".txt")) {
				ByteBuffer d = t.data();
				byte[] raw = new byte[d.remaining()];
				d.get(raw);
				int len = 0;
				while (len < raw.length && raw[len] != 0) {
					len++;
				}
				return Optional.of(new MessageTable(new String(raw, 0, len, StandardCharsets.UTF_8)));
			}
		}
		return Optional.empty();
	}

	public Map<Integer, String> all() {
		return messages;
	}

	public String get(int id) {
		return messages.get(id);
	}

	/** FLP 변수명 {@code PS2_<ID>} → 메시지 (아니면 null). */
	public String forVariable(String var) {
		if (var != null && var.startsWith("PS2_")) {
			try {
				return messages.get(Integer.parseInt(var.substring(4)));
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}
}
