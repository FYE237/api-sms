package net.sf.briar.serial;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import junit.framework.TestCase;
import net.sf.briar.api.serial.FormatException;
import net.sf.briar.api.serial.Raw;
import net.sf.briar.util.StringUtils;

import org.junit.Test;

public class ReaderImplTest extends TestCase {

	private ByteArrayInputStream in = null;
	private ReaderImpl r = null;

	@Test
	public void testReadBoolean() throws IOException {
		setContents("FFFE");
		assertFalse(r.readBoolean());
		assertTrue(r.readBoolean());
		assertTrue(r.eof());
	}

	@Test
	public void testReadInt8() throws IOException {
		setContents("FD00" + "FDFF" + "FD7F" + "FD80");
		assertEquals((byte) 0, r.readInt8());
		assertEquals((byte) -1, r.readInt8());
		assertEquals(Byte.MAX_VALUE, r.readInt8());
		assertEquals(Byte.MIN_VALUE, r.readInt8());
		assertTrue(r.eof());
	}

	@Test
	public void testReadInt16() throws IOException {
		setContents("FC0000" + "FCFFFF" + "FC7FFF" + "FC8000");
		assertEquals((short) 0, r.readInt16());
		assertEquals((short) -1, r.readInt16());
		assertEquals(Short.MAX_VALUE, r.readInt16());
		assertEquals(Short.MIN_VALUE, r.readInt16());
		assertTrue(r.eof());
	}

	@Test
	public void testReadInt32() throws IOException {
		setContents("FB00000000" + "FBFFFFFFFF" + "FB7FFFFFFF" + "FB80000000");
		assertEquals(0, r.readInt32());
		assertEquals(-1, r.readInt32());
		assertEquals(Integer.MAX_VALUE, r.readInt32());
		assertEquals(Integer.MIN_VALUE, r.readInt32());
		assertTrue(r.eof());
	}

	@Test
	public void testReadInt64() throws IOException {
		setContents("FA0000000000000000" + "FAFFFFFFFFFFFFFFFF" +
				"FA7FFFFFFFFFFFFFFF" + "FA8000000000000000");
		assertEquals(0L, r.readInt64());
		assertEquals(-1L, r.readInt64());
		assertEquals(Long.MAX_VALUE, r.readInt64());
		assertEquals(Long.MIN_VALUE, r.readInt64());
		assertTrue(r.eof());
	}

	@Test
	public void testReadIntAny() throws IOException {
		setContents("00" + "7F" + "FD80" + "FDFF" + "FC0080" + "FC7FFF" +
				"FB00008000" + "FB7FFFFFFF" + "FA0000000080000000");
		assertEquals(0L, r.readIntAny());
		assertEquals(127L, r.readIntAny());
		assertEquals(-128L, r.readIntAny());
		assertEquals(-1L, r.readIntAny());
		assertEquals(128L, r.readIntAny());
		assertEquals(32767L, r.readIntAny());
		assertEquals(32768L, r.readIntAny());
		assertEquals(2147483647L, r.readIntAny());
		assertEquals(2147483648L, r.readIntAny());
		assertTrue(r.eof());
	}

	@Test
	public void testReadFloat32() throws IOException {
		// http://babbage.cs.qc.edu/IEEE-754/Decimal.html
		// http://steve.hollasch.net/cgindex/coding/ieeefloat.html
		setContents("F900000000" + "F93F800000" + "F940000000" + "F9BF800000" +
				"F980000000" + "F9FF800000" + "F97F800000" + "F97FC00000");
		assertEquals(0F, r.readFloat32());
		assertEquals(1F, r.readFloat32());
		assertEquals(2F, r.readFloat32());
		assertEquals(-1F, r.readFloat32());
		assertEquals(-0F, r.readFloat32());
		assertEquals(Float.NEGATIVE_INFINITY, r.readFloat32());
		assertEquals(Float.POSITIVE_INFINITY, r.readFloat32());
		assertTrue(Float.isNaN(r.readFloat32()));
		assertTrue(r.eof());
	}

	@Test
	public void testReadFloat64() throws IOException {
		setContents("F80000000000000000" + "F83FF0000000000000" +
				"F84000000000000000" + "F8BFF0000000000000" +
				"F88000000000000000" + "F8FFF0000000000000" +
				"F87FF0000000000000" + "F87FF8000000000000");
		assertEquals(0.0, r.readFloat64());
		assertEquals(1.0, r.readFloat64());
		assertEquals(2.0, r.readFloat64());
		assertEquals(-1.0, r.readFloat64());
		assertEquals(-0.0, r.readFloat64());
		assertEquals(Double.NEGATIVE_INFINITY, r.readFloat64());
		assertEquals(Double.POSITIVE_INFINITY, r.readFloat64());
		assertTrue(Double.isNaN(r.readFloat64()));
		assertTrue(r.eof());
	}

	@Test
	public void testReadUtf8() throws IOException {
		setContents("F703666F6F" + "F703666F6F" + "F700");
		assertEquals("foo", r.readUtf8());
		assertEquals("foo", r.readUtf8());
		assertEquals("", r.readUtf8());
		assertTrue(r.eof());
	}

	@Test
	public void testReadUtf8MaxLengthNotExceeded() throws IOException {
		setContents("F703666F6F");
		assertEquals("foo", r.readUtf8(3));
		assertTrue(r.eof());
	}

	@Test
	public void testReadUtf8MaxLengthExceeded() throws IOException {
		setContents("F703666F6F");
		try {
			r.readUtf8(2);
			assertTrue(false);
		} catch(FormatException expected) {}
	}

	@Test
	public void testReadRaw() throws IOException {
		setContents("F603010203" + "F603010203" + "F600");
		assertTrue(Arrays.equals(new byte[] {1, 2, 3}, r.readRaw()));
		assertTrue(Arrays.equals(new byte[] {1, 2, 3}, r.readRaw()));
		assertTrue(Arrays.equals(new byte[] {}, r.readRaw()));
		assertTrue(r.eof());
	}

	@Test
	public void testReadRawMaxLengthNotExceeded() throws IOException {
		setContents("F603010203");
		assertTrue(Arrays.equals(new byte[] {1, 2, 3}, r.readRaw(3)));
		assertTrue(r.eof());
	}

	@Test
	public void testReadRawMaxLengthExceeded() throws IOException {
		setContents("F603010203");
		try {
			r.readRaw(2);
			assertTrue(false);
		} catch(FormatException expected) {}
	}

	@Test
	public void testReadDefiniteList() throws IOException {
		setContents("F5" + "03" + "01" + "F703666F6F" + "FC0080");
		List<Object> l = r.readList();
		assertNotNull(l);
		assertEquals(3, l.size());
		assertEquals((byte) 1, l.get(0));
		assertEquals("foo", l.get(1));
		assertEquals((short) 128, l.get(2));
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteListElements() throws IOException {
		setContents("F5" + "03" + "01" + "F703666F6F" + "FC0080");
		Iterator<Object> i = r.readListElements();
		assertNotNull(i);
		assertTrue(i.hasNext());
		assertEquals((byte) 1, i.next());
		assertTrue(i.hasNext());
		assertEquals("foo", i.next());
		assertTrue(i.hasNext());
		assertEquals((short) 128, i.next());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteListTypeSafe() throws IOException {
		setContents("F5" + "03" + "01" + "02" + "03");
		List<Byte> l = r.readList(Byte.class);
		assertNotNull(l);
		assertEquals(3, l.size());
		assertEquals(Byte.valueOf((byte) 1), l.get(0));
		assertEquals(Byte.valueOf((byte) 2), l.get(1));
		assertEquals(Byte.valueOf((byte) 3), l.get(2));
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteListElementsTypeSafe() throws IOException {
		setContents("F5" + "03" + "01" + "02" + "03");
		Iterator<Byte> i = r.readListElements(Byte.class);
		assertNotNull(i);
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 1), i.next());
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 2), i.next());
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 3), i.next());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteMap() throws IOException {
		setContents("F4" + "02" + "F703666F6F" + "7B" + "F600" + "F0");
		Map<Object, Object> m = r.readMap();
		assertNotNull(m);
		assertEquals(2, m.size());
		assertEquals((byte) 123, m.get("foo"));
		Raw raw = new RawImpl(new byte[] {});
		assertTrue(m.containsKey(raw));
		assertNull(m.get(raw));
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteMapEntries() throws IOException {
		setContents("F4" + "02" + "F703666F6F" + "7B" + "F600" + "F0");
		Iterator<Entry<Object, Object>> i = r.readMapEntries();
		assertNotNull(i);
		assertTrue(i.hasNext());
		Entry<Object, Object> e = i.next();
		assertNotNull(e);
		assertEquals("foo", e.getKey());
		assertEquals((byte) 123, e.getValue());
		assertTrue(i.hasNext());
		e = i.next();
		assertNotNull(e);
		assertEquals(new RawImpl(new byte[] {}), e.getKey());
		assertNull(e.getValue());
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteMapTypeSafe() throws IOException {
		setContents("F4" + "02" + "F703666F6F" + "7B" + "F700" + "F0");
		Map<String, Byte> m = r.readMap(String.class, Byte.class);
		assertNotNull(m);
		assertEquals(2, m.size());
		assertEquals(Byte.valueOf((byte) 123), m.get("foo"));
		assertTrue(m.containsKey(""));
		assertNull(m.get(""));
		assertTrue(r.eof());
	}

	@Test
	public void testReadDefiniteMapEntriesTypeSafe() throws IOException {
		setContents("F4" + "02" + "F703666F6F" + "7B" + "F700" + "F0");
		Iterator<Entry<String, Byte>> i =
			r.readMapEntries(String.class, Byte.class);
		assertNotNull(i);
		assertTrue(i.hasNext());
		Entry<String, Byte> e = i.next();
		assertNotNull(e);
		assertEquals("foo", e.getKey());
		assertEquals(Byte.valueOf((byte) 123), e.getValue());
		assertTrue(i.hasNext());
		e = i.next();
		assertNotNull(e);
		assertEquals("", e.getKey());
		assertNull(e.getValue());
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteList() throws IOException {
		setContents("F3" + "01" + "F703666F6F" + "FC0080" + "F1");
		List<Object> l = r.readList();
		assertNotNull(l);
		assertEquals(3, l.size());
		assertEquals((byte) 1, l.get(0));
		assertEquals("foo", l.get(1));
		assertEquals((short) 128, l.get(2));
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndfiniteListElements() throws IOException {
		setContents("F3" + "01" + "F703666F6F" + "FC0080" + "F1");
		Iterator<Object> i = r.readListElements();
		assertNotNull(i);
		assertTrue(i.hasNext());
		assertEquals((byte) 1, i.next());
		assertTrue(i.hasNext());
		assertEquals("foo", i.next());
		assertTrue(i.hasNext());
		assertEquals((short) 128, i.next());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteListTypeSafe() throws IOException {
		setContents("F3" + "01" + "02" + "03" + "F1");
		List<Byte> l = r.readList(Byte.class);
		assertNotNull(l);
		assertEquals(3, l.size());
		assertEquals(Byte.valueOf((byte) 1), l.get(0));
		assertEquals(Byte.valueOf((byte) 2), l.get(1));
		assertEquals(Byte.valueOf((byte) 3), l.get(2));
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteListElementsTypeSafe() throws IOException {
		setContents("F3" + "01" + "02" + "03" + "F1");
		Iterator<Byte> i = r.readListElements(Byte.class);
		assertNotNull(i);
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 1), i.next());
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 2), i.next());
		assertTrue(i.hasNext());
		assertEquals(Byte.valueOf((byte) 3), i.next());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteMap() throws IOException {
		setContents("F2" + "F703666F6F" + "7B" + "F600" + "F0" + "F1");
		Map<Object, Object> m = r.readMap();
		assertNotNull(m);
		assertEquals(2, m.size());
		assertEquals((byte) 123, m.get("foo"));
		Raw raw = new RawImpl(new byte[] {});
		assertTrue(m.containsKey(raw));
		assertNull(m.get(raw));
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteMapEntries() throws IOException {
		setContents("F2" + "F703666F6F" + "7B" + "F600" + "F0" + "F1");
		Iterator<Entry<Object, Object>> i = r.readMapEntries();
		assertNotNull(i);
		assertTrue(i.hasNext());
		Entry<Object, Object> e = i.next();
		assertNotNull(e);
		assertEquals("foo", e.getKey());
		assertEquals((byte) 123, e.getValue());
		assertTrue(i.hasNext());
		e = i.next();
		assertNotNull(e);
		assertEquals(new RawImpl(new byte[] {}), e.getKey());
		assertNull(e.getValue());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteMapTypeSafe() throws IOException {
		setContents("F2" + "F703666F6F" + "7B" + "F700" + "F0" + "F1");
		Map<String, Byte> m = r.readMap(String.class, Byte.class);
		assertNotNull(m);
		assertEquals(2, m.size());
		assertEquals(Byte.valueOf((byte) 123), m.get("foo"));
		assertTrue(m.containsKey(""));
		assertNull(m.get(""));
		assertTrue(r.eof());
	}

	@Test
	public void testReadIndefiniteMapEntriesTypeSafe() throws IOException {
		setContents("F2" + "F703666F6F" + "7B" + "F700" + "F0" + "F1");
		Iterator<Entry<String, Byte>> i =
			r.readMapEntries(String.class, Byte.class);
		assertNotNull(i);
		assertTrue(i.hasNext());
		Entry<String, Byte> e = i.next();
		assertNotNull(e);
		assertEquals("foo", e.getKey());
		assertEquals(Byte.valueOf((byte) 123), e.getValue());
		assertTrue(i.hasNext());
		e = i.next();
		assertNotNull(e);
		assertEquals("", e.getKey());
		assertNull(e.getValue());
		assertFalse(i.hasNext());
		assertTrue(r.eof());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testReadNestedMapsAndLists() throws IOException {
		setContents("F4" + "01" + "F4" + "01" + "F703666F6F" + "7B" +
				"F5" + "01" + "01");
		Map<Object, Object> m = r.readMap();
		assertNotNull(m);
		assertEquals(1, m.size());
		Entry<Object, Object> e = m.entrySet().iterator().next();
		Map<Object, Object> m1 = (Map<Object, Object>) e.getKey();
		assertNotNull(m1);
		assertEquals(1, m1.size());
		assertEquals((byte) 123, m1.get("foo"));
		List<Object> l = (List<Object>) e.getValue();
		assertNotNull(l);
		assertEquals(1, l.size());
		assertEquals((byte) 1, l.get(0));
		assertTrue(r.eof());
	}

	private void setContents(String hex) {
		in = new ByteArrayInputStream(StringUtils.fromHexString(hex));
		r = new ReaderImpl(in);
	}
}
