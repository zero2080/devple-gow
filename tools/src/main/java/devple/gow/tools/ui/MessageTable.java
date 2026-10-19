package devple.gow.tools.ui;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
 */
public final class MessageTable {

	/** 메시지 테이블이 들어 있는 원시 파일 태그 ID (MC_ICON 과 같은 0x6F). */
	public static final int RAW_FILE_TAG = 0x6F;
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

	/** WAD 안에서 이름이 msgs*.txt 인 원시 파일 태그를 찾아 파싱. */
	public static Optional<MessageTable> fromWad(WadFile wad) {
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
