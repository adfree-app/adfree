//! WebSocket frames, read off the relay after a `101 Switching Protocols`.
//!
//! From the switch on, the connection carries no HTTP: the bytes are frames,
//! and a tracker that kept looking for heads would show one row and nothing
//! else. This reads RFC 6455 as it goes — a frame may be split across any
//! number of reads, and a read may hold any number of frames — so the caller
//! can hand over whatever the TLS half produced.
//!
//! Only the first [`MAX_PAYLOAD`] bytes of a payload are kept. A socket
//! transcript is read to answer "what is this page saying", and the beginning
//! of a frame answers it; the length is recorded whole, so a 4 MB message still
//! shows as 4 MB.
//!
//! Nothing here decides anything: a frame that breaks the format is dropped and
//! the parser resynchronises on the next byte, because a capture window must
//! never be the reason a working socket stalls.

/// Bytes of a payload kept for the preview.
pub const MAX_PAYLOAD: usize = 512;

/// Longest frame head: two fixed bytes, an eight-byte length and a four-byte
/// mask key.
const MAX_HEAD: usize = 14;

/// One frame as it crossed the connection.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Frame {
    /// Whether this frame ends the message it belongs to.
    pub fin: bool,
    /// The opcode: 1 text, 2 binary, 8 close, 9 ping, 10 pong, 0 continuation.
    pub opcode: u8,
    /// The payload length as the frame declared it, preview or not.
    pub len: usize,
    /// The first [`MAX_PAYLOAD`] payload bytes, unmasked.
    pub payload: Vec<u8>,
}

/// A frame whose payload is still arriving.
struct Chunk {
    fin: bool,
    opcode: u8,
    len: usize,
    left: usize,
    keep: Vec<u8>,
    mask: Option<([u8; 4], usize)>,
}

/// Reads frames out of a byte stream, one connection's worth.
#[derive(Default)]
pub struct Parser {
    head: Vec<u8>,
    chunk: Option<Chunk>,
}

impl Parser {
    /// Feeds whatever arrived and returns the frames that are complete now.
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<Frame> {
        let mut out = Vec::new();
        for &byte in bytes {
            match self.chunk.as_mut() {
                None => {
                    self.head.push(byte);
                    let Some(head) = parse_head(&self.head) else {
                        if self.head.len() > MAX_HEAD {
                            // Not a frame head, and more bytes will not make it
                            // one: drop what we have and start over.
                            self.head.clear();
                        }
                        continue;
                    };
                    if self.head.len() < head.head_len {
                        continue;
                    }
                    self.head.clear();
                    if head.len == 0 {
                        out.push(Frame {
                            fin: head.fin,
                            opcode: head.opcode,
                            len: head.len,
                            payload: Vec::new(),
                        });
                    } else {
                        self.chunk = Some(Chunk {
                            fin: head.fin,
                            opcode: head.opcode,
                            len: head.len,
                            left: head.len,
                            keep: Vec::new(),
                            mask: head.mask,
                        });
                    }
                }
                Some(chunk) => {
                    let value = match &mut chunk.mask {
                        // A client's frame is masked, and the mask advances over
                        // every payload byte, kept or not.
                        Some((key, position)) => {
                            let value = byte ^ key[*position % 4];
                            *position += 1;
                            value
                        }
                        None => byte,
                    };
                    if chunk.keep.len() < MAX_PAYLOAD {
                        chunk.keep.push(value);
                    }
                    chunk.left -= 1;
                    if chunk.left == 0 {
                        let done = self.chunk.take().expect("the chunk was just read");
                        out.push(Frame {
                            fin: done.fin,
                            opcode: done.opcode,
                            len: done.len,
                            payload: done.keep,
                        });
                    }
                }
            }
        }
        out
    }
}

/// The frame head, once enough of it has arrived.
struct FrameHead {
    fin: bool,
    opcode: u8,
    len: usize,
    mask: Option<([u8; 4], usize)>,
    /// How many bytes the head occupies, mask and extended length included.
    head_len: usize,
}

fn parse_head(head: &[u8]) -> Option<FrameHead> {
    if head.len() < 2 {
        return None;
    }
    let fin = head[0] & 0x80 != 0;
    let opcode = head[0] & 0x0f;
    let masked = head[1] & 0x80 != 0;
    let (len, mut offset) = match head[1] & 0x7f {
        126 => {
            if head.len() < 4 {
                return None;
            }
            (((head[2] as usize) << 8) | head[3] as usize, 4)
        }
        127 => {
            if head.len() < 10 {
                return None;
            }
            let mut len = 0usize;
            for byte in &head[2..10] {
                len = (len << 8) | *byte as usize;
            }
            (len, 10)
        }
        short => (short as usize, 2),
    };
    let mask = if masked {
        if head.len() < offset + 4 {
            return None;
        }
        let key = [
            head[offset],
            head[offset + 1],
            head[offset + 2],
            head[offset + 3],
        ];
        offset += 4;
        Some((key, 0))
    } else {
        None
    };
    Some(FrameHead {
        fin,
        opcode,
        len,
        mask,
        head_len: offset,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(payload: &str, masked: bool) -> Vec<u8> {
        let mut frame = vec![0x81];
        let len = payload.len();
        if masked {
            frame.push(0x80 | len as u8);
            frame.extend_from_slice(&[1, 2, 3, 4]);
            for (index, byte) in payload.bytes().enumerate() {
                frame.push(byte ^ [1u8, 2, 3, 4][index % 4]);
            }
        } else {
            frame.push(len as u8);
            frame.extend_from_slice(payload.as_bytes());
        }
        frame
    }

    #[test]
    fn a_text_frame_is_read_whole() {
        let mut parser = Parser::default();
        let frames = parser.feed(&text("hello", false));
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].opcode, 1);
        assert_eq!(frames[0].len, 5);
        assert_eq!(frames[0].payload, b"hello");
        assert!(frames[0].fin);
    }

    #[test]
    fn a_client_frame_arrives_masked_and_is_unmasked() {
        let mut parser = Parser::default();
        let frames = parser.feed(&text("get me", true));
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].payload, b"get me");
    }

    #[test]
    fn two_frames_in_one_read_both_come_out() {
        let mut parser = Parser::default();
        let mut bytes = text("one", false);
        bytes.extend_from_slice(&text("two", false));
        let frames = parser.feed(&bytes);
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].payload, b"one");
        assert_eq!(frames[1].payload, b"two");
    }

    #[test]
    fn a_frame_split_across_reads_waits_for_the_rest() {
        let mut parser = Parser::default();
        let bytes = text("split", false);
        let first = parser.feed(&bytes[..4]);
        assert!(first.is_empty());
        let second = parser.feed(&bytes[4..]);
        assert_eq!(second.len(), 1);
        assert_eq!(second[0].payload, b"split");
    }

    #[test]
    fn a_long_payload_keeps_its_length_and_a_short_preview() {
        let mut parser = Parser::default();
        let long = "x".repeat(700);
        let frames = parser.feed(&{
            let mut head = vec![0x82, 126];
            head.extend_from_slice(&(700u16).to_be_bytes());
            head.extend_from_slice(long.as_bytes());
            head
        });
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].len, 700);
        assert_eq!(frames[0].payload.len(), MAX_PAYLOAD);
        assert_eq!(frames[0].opcode, 2);
    }

    #[test]
    fn a_ping_with_no_payload_is_a_frame_too() {
        let mut parser = Parser::default();
        let frames = parser.feed(&[0x89, 0x00]);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].opcode, 9);
        assert_eq!(frames[0].len, 0);
    }

    #[test]
    fn a_masked_frame_keeps_its_mask_running_past_the_preview() {
        // A payload longer than the preview: the mask has to advance over the
        // bytes that were dropped, or the next frame would be read with the
        // wrong key position.
        let payload = vec![7u8; MAX_PAYLOAD + 10];
        let mut frame = vec![0x82, 0x80 | 126];
        frame.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        frame.extend_from_slice(&[9, 8, 7, 6]);
        for (index, byte) in payload.iter().enumerate() {
            frame.push(byte ^ [9u8, 8, 7, 6][index % 4]);
        }
        let mut parser = Parser::default();
        let frames = parser.feed(&frame);
        assert_eq!(frames.len(), 1);
        assert!(frames[0].payload.iter().all(|byte| *byte == 7));
        // And the next frame still reads cleanly.
        let next = parser.feed(&text("after", false));
        assert_eq!(next.len(), 1);
        assert_eq!(next[0].payload, b"after");
    }
}
