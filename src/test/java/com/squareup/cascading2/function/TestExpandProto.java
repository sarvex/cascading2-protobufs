package com.squareup.cascading2.function;

import cascading.flow.hadoop.HadoopFlowConnector;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.pipe.Each;
import cascading.pipe.Pipe;
import cascading.scheme.hadoop.TextLine;
import cascading.tap.hadoop.Hfs;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.TupleEntryIterator;
import com.squareup.cascading2.generated.Example;
import com.squareup.cascading2.scheme.ProtobufScheme;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import junit.framework.TestCase;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class TestExpandProto extends TestCase {

  private static final Example.Person.Builder BRYAN = Example.Person
      .newBuilder()
      .setName("bryan")
      .setId(1)
      .setEmail("bryan@mail.com")
      .setPosition(Example.Person.Position.CEO);
  private static final Example.Person.Builder LUCAS =
      Example.Person.newBuilder().setName("lucas").setId(2);
  private static final Example.Person.Builder TOM =
      Example.Person.newBuilder().setName("tom").setId(3);
  private static final Example.Person.Builder DICK =
      Example.Person.newBuilder().setName("dick").setId(3);
  private static final Example.Person.Builder HARRY =
      Example.Person.newBuilder().setName("harry").setId(3);

  public void testSimple() throws Exception {
    AbstractExpandProto allFields = new ExpandProto(Example.Person.class);
    List<Tuple> results =
        operateFunction(allFields, new TupleEntry(new Fields("value"), new Tuple(BRYAN.build())));
    Tuple result = results.get(0);
    assertEquals(new Tuple(1, "bryan", "bryan@mail.com", Example.Person.Position.CEO.getNumber()), result);

    results =
        operateFunction(allFields, new TupleEntry(new Fields("value"), new Tuple(LUCAS.build())));
    result = results.get(0);
    assertEquals(new Tuple(2, "lucas", null, null), result);
  }

  public void testNested() throws Exception {
    AbstractExpandProto allFields = new ExpandProto(Example.Partnership.class, "leader", "follower");
    List<Tuple> results = operateFunction(allFields, new TupleEntry(new Fields("value"), new Tuple(
        Example.Partnership
            .newBuilder()
            .setLeader(BRYAN)
            .setFollower(LUCAS)
            .build())));
    assertEquals(new Tuple(BRYAN.build(), LUCAS.build()), results.get(0));

    results = operateFunction(new ExpandProto(Example.Partnership.class, "leader"),
        new TupleEntry(new Fields("value"), new Tuple(Example.Partnership
            .newBuilder()
            .setLeader(BRYAN)
            .setFollower(LUCAS)
            .build())));
    assertEquals(new Tuple(BRYAN.build()), results.get(0));
  }

  public void testRepeated() throws Exception {
    try {
      new ExpandProto(Example.Partnership.class, "silent");
      fail("this should have thrown an exception!");
    } catch(IllegalArgumentException e) {
      // ok
    }
  }

  public void testConstructorErrorCases() throws Exception {
    try {
      new ExpandProto<Example.Person>(null);
      fail("should throw NPE on null message class");
    } catch(NullPointerException e) {
      // ok
    }

    try {
      new ExpandProto<Example.Person>(Example.Person.class, new Fields("1"));
      fail("should throw exception with non-found field");
    } catch(IllegalArgumentException e) {
      // ok
    }

    try {
      new ExpandProto<Example.Person>(Example.Person.class, "1");
      fail("should throw exception with non-found field");
    } catch(IllegalArgumentException e) {
      // ok
    }

    try {
      new ExpandProto<Example.Person>(Example.Person.class, new Fields("1"), "id", "name");
      fail("should throw exception with arg length mismatch");
    } catch(IllegalArgumentException e) {
      // ok
    }
  }

  public void testWrongArgumentClass() throws Exception {
    AbstractExpandProto func = new ExpandProto(Example.Person.class, "name");
    try {
      func.operate(new HadoopFlowProcess(), new FunctionCall() {
        @Override public TupleEntry getArguments() {
          return new TupleEntry(new Fields("partnership"), new Tuple(Example.Partnership.newBuilder().setFollower(
              Example.Person.newBuilder().setName("bryan")).setLeader(Example.Person.newBuilder().setName("alsoBryan")).build()));
        }

        @Override public Fields getDeclaredFields() {
          return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override public TupleEntryCollector getOutputCollector() {
          return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override public Object getContext() {
          return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override public void setContext(Object o) {
          //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override public Fields getArgumentFields() {
          return null;  //To change body of implemented methods use File | Settings | File Templates.
        }
      });
      fail("should have thrown an IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      // expected!
    }
  }

  public void testInFlow() throws Exception {
    FileSystem.get(new Configuration()).delete(new Path("/tmp/input"), true);
    FileSystem.get(new Configuration()).delete(new Path("/tmp/output"), true);

    Hfs inTap = new Hfs(new ProtobufScheme("value", Example.Person.class), "/tmp/input");
    TupleEntryCollector collector = inTap.openForWrite(new HadoopFlowProcess());
    collector.add(new TupleEntry(new Fields("value"), new Tuple(BRYAN.build())));
    collector.add(new TupleEntry(new Fields("value"), new Tuple(LUCAS.build())));
    collector.close();

    Pipe inPipe = new Pipe("in");
    Pipe p = new Each(inPipe, new Fields("value"), new ExpandProto(Example.Person.class), new Fields("id", "name", "email", "position"));

    Hfs sink = new Hfs(new TextLine(), "/tmp/output");
    new HadoopFlowConnector().connect(inTap, sink, p).complete();

    TupleEntryIterator iter = sink.openForRead(new HadoopFlowProcess());
    List<Tuple> results = new ArrayList<Tuple>();
    while (iter.hasNext()) {
      results.add(iter.next().getTupleCopy());
    }
    assertEquals(2, results.size());

    assertEquals(new Tuple(0, 1, "bryan", "bryan@mail.com", Example.Person.Position.CEO.getNumber()).toString(), results.get(0).toString());
    assertEquals(new Tuple(25, 2, "lucas", null, null).toString(), results.get(1).toString());
  }

  public void testGetEmittedClasses() throws Exception {
    AbstractExpandProto<Example.Partnership> func = new ExpandProto<Example.Partnership>(Example.Partnership.class, "leader");
    assertEquals(Collections.singleton(Example.Person.class), func.getEmittedClasses());
  }

  protected static List<Tuple> operateFunction(Function func, final TupleEntry argument) {
    final List<Tuple> results = new ArrayList<Tuple>();
    func.operate(null, new FunctionCall() {
      @Override public TupleEntry getArguments() {
        return argument;
      }

      @Override public Fields getDeclaredFields() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
      }

      @Override public TupleEntryCollector getOutputCollector() {
        return new TupleEntryCollector() {
          @Override protected void collect(TupleEntry tupleEntry) throws IOException {
            results.add(tupleEntry.getTuple());
          }
        };
      }

      @Override public Object getContext() {
        throw new UnsupportedOperationException();
      }

      @Override public void setContext(Object o) {
        throw new UnsupportedOperationException();
      }

      @Override public Fields getArgumentFields() {
        return argument.getFields();
      }
    });

    return results;
  }
}
