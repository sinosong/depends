class Type1:
  def f1_1(self):
    pass

class Type2:
  def f2_1(self):
    pass

class Type3:
  def f1_1(self):
    pass
  def f3_1(self):
    pass

def test(t1):
  t1.f1_1(self)

class ConnectionMock(object):
    def send(self, data):
        self.received += data

def test_expression(connection):
  connection.send(msg.to_bytes())
