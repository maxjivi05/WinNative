pub fn hash(data: &[u8], high: u32, low: u32) -> (u32, u32) {
    let mut a = 0xdeadbeefu32
        .wrapping_add(data.len() as u32)
        .wrapping_add(high);
    let mut b = a;
    let mut c = a.wrapping_add(low);
    let mut bytes = data;
    while bytes.len() > 12 {
        a = a.wrapping_add(u32::from_le_bytes(bytes[..4].try_into().unwrap()));
        b = b.wrapping_add(u32::from_le_bytes(bytes[4..8].try_into().unwrap()));
        c = c.wrapping_add(u32::from_le_bytes(bytes[8..12].try_into().unwrap()));
        a = a.wrapping_sub(c) ^ c.rotate_left(4);
        c = c.wrapping_add(b);
        b = b.wrapping_sub(a) ^ a.rotate_left(6);
        a = a.wrapping_add(c);
        c = c.wrapping_sub(b) ^ b.rotate_left(8);
        b = b.wrapping_add(a);
        a = a.wrapping_sub(c) ^ c.rotate_left(16);
        c = c.wrapping_add(b);
        b = b.wrapping_sub(a) ^ a.rotate_left(19);
        a = a.wrapping_add(c);
        c = c.wrapping_sub(b) ^ b.rotate_left(4);
        b = b.wrapping_add(a);
        bytes = &bytes[12..];
    }
    if bytes.is_empty() {
        return (c, b);
    }
    for (n, byte) in bytes.iter().enumerate() {
        let value = (*byte as u32) << ((n % 4) * 8);
        match n / 4 {
            0 => a = a.wrapping_add(value),
            1 => b = b.wrapping_add(value),
            _ => c = c.wrapping_add(value),
        }
    }
    c = (c ^ b).wrapping_sub(b.rotate_left(14));
    a = (a ^ c).wrapping_sub(c.rotate_left(11));
    b = (b ^ a).wrapping_sub(a.rotate_left(25));
    c = (c ^ b).wrapping_sub(b.rotate_left(16));
    a = (a ^ c).wrapping_sub(c.rotate_left(4));
    b = (b ^ a).wrapping_sub(a.rotate_left(14));
    c = (c ^ b).wrapping_sub(b.rotate_left(24));
    (c, b)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_reference_casc_header() {
        assert_eq!(hash(&[], 0, 0).0, 0xdeadbeef);
        assert_eq!(
            hash(&[7, 0, 0, 0, 4, 5, 9, 30, 0, 0, 0, 192, 255, 0, 0, 0], 0, 0).0,
            0x1bc0046b
        );
    }
}
