package devple.gow.tools.pak;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GODOFWAR.TOC 파서. 엔트리 배열이 끝나면 0으로 채워진 레코드(12바이트 이상)가 온다. */
public final class GowToc {

	public static final int ENTRY_SIZE = 24;

	private final List<TocEntry> entries;

	public GowToc(ByteBuffer raw) {
		ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		List<TocEntry> list = new ArrayList<>();
		for (int off = 0; off + ENTRY_SIZE <= b.limit(); off += ENTRY_SIZE) {
			byte[] nameBytes = new byte[12];
			b.get(off, nameBytes);
			int len = 0;
			while (len < 12 && nameBytes[len] != 0) {
				len++;
			}
			if (len == 0) {
				break;
			}
			list.add(new TocEntry(new String(nameBytes, 0, len, StandardCharsets.US_ASCII),
					b.getInt(off + 12),
					Integer.toUnsignedLong(b.getInt(off + 16)),
					Integer.toUnsignedLong(b.getInt(off + 20))));
		}
		this.entries = Collections.unmodifiableList(list);
	}

	public List<TocEntry> entries() {
		return entries;
	}

	/** 이름별 첫 번째 사본만 남긴다. */
	public Map<String, TocEntry> unique() {
		Map<String, TocEntry> m = new LinkedHashMap<>();
		for (TocEntry e : entries) {
			m.putIfAbsent(e.name(), e);
		}
		return m;
	}
}
