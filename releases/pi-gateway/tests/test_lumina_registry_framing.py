"""The privileged helper handles fragmented bounded Unix-stream messages."""
import socket, threading, unittest
from no5_pi_gateway.lumina_registry_admin import _read_frame, MAX_FRAME

class RegistryFramingTest(unittest.TestCase):
    def test_fragmented_message_is_read_until_one_newline(self):
        reader, writer=socket.socketpair()
        try:
            writer.sendall(b'{"op":"re')
            timer=threading.Timer(.02,lambda:writer.sendall(b'voke","keyId":"123"}\n'));timer.start()
            self.assertEqual({'op':'revoke','keyId':'123'},_read_frame(reader));timer.join()
        finally:reader.close();writer.close()
    def test_oversize_and_multiple_messages_are_rejected(self):
        for frame in [b'x'*(MAX_FRAME+1),b'{}\n{}\n']:
            reader,writer=socket.socketpair()
            try:
                writer.sendall(frame)
                with self.assertRaises(ValueError):_read_frame(reader)
            finally:reader.close();writer.close()
