package com.twilio.raas.sql;

import com.google.common.annotations.VisibleForTesting;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.EnumerableDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Queue;

import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.function.Function2;

import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.AbstractEnumerable2;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.Schema;

/**
 * An {@link Enumerable} that *can* returns Kudu records in Ascending order on
 * their primary key. It does so by wrapping a {@link List} of
 * {@link CalciteKuduEnumerable}, querying each one for their next row and
 * comparing those rows. This requires each {@code CalciteKuduEnumerable} scan
 * only one {@link org.apache.kudu.client.Partition} within the Kudu Table.
 * This guarantees the first rows coming out of the
 * {@link org.apache.kudu.client.AsyncKuduScanner} will return rows sorted by
 * primary key.
 *
 * Enumerable will return in unsorted order unless
 * {@link SortableEnumerable#setSorted} is called.
 */
public final class SortableEnumerable extends AbstractEnumerable<Object> {
  private static final Logger logger = LoggerFactory.getLogger(SortableEnumerable.class);

  private final List<AsyncKuduScanner> scanners;

  private final AtomicBoolean scansShouldStop;
  private final Schema projectedSchema;
  private final Schema tableSchema;

  public final boolean sort;
  public final boolean groupBySorted;
  public final long limit;
  public final long offset;
  public final List<Integer> descendingSortedFieldIndices;
  public final KuduScanStats scanStats;

  /**
   * A SortableEnumerable is an {@link Enumerable} for Kudu that can be configured to be sorted.
   *
   * @param scanners a List of running {@link AsyncKuduScanner}
   * @param scansShouldStop signal to be used by this class to stop the scanners
   * @param tableSchema {@link Schema} for the kudu table
   * @param limit the number of rows this should return. -1 indicates no limit
   * @param offset the number of rows from kudu to skip prior to returning rows
   * @param sort whether or not have Kudu RPCs come back in sorted by primary key
   * @param descendingSortedFieldIndices is a list of column indices that are sorted in reverse
   * @param groupBySorted when sorted, and {@link Enumerable#groupBy(Function1, Function0, Function2, Function2)
   * @param scanStats a container of scan stats that should be updated as the scan executes.
   *
   * @throws IllegalArgumentException when groupByLimited is true but sorted is false
   */
  public SortableEnumerable(
      List<AsyncKuduScanner> scanners,
      final AtomicBoolean scansShouldStop,
      final Schema projectedSchema,
      final Schema tableSchema,
      final long limit,
      final long offset,
      final boolean sort,
      final List<Integer> descendingSortedFieldIndices,
      final boolean groupBySorted,
      final KuduScanStats scanStats) {
    this.scanners = scanners;
    this.scansShouldStop = scansShouldStop;
    this.projectedSchema = projectedSchema;
    this.tableSchema = tableSchema;
    this.limit = limit;
    this.offset = offset;
        // if we have an offset always sort by the primary key to ensure the rows are returned
    // in a predictible order
    this.sort = offset>0 || sort;
    this.descendingSortedFieldIndices = descendingSortedFieldIndices;
    if (groupBySorted && !this.sort) {
      throw new IllegalArgumentException("If groupBySorted is true the results must need to be " +
          "sorted");
    }
    this.groupBySorted = groupBySorted;
    this.scanStats = scanStats;
  }

  @VisibleForTesting
  List<AsyncKuduScanner> getScanners() {
    return scanners;
  }

  private boolean checkLimitReached(int totalMoves) {
    // handling of limit and/or offset for groupBySorted is done in the groupBy method
    if (limit > 0 && !groupBySorted) {
      long moveOffset = offset > 0 ? offset : 0;
      if (totalMoves - moveOffset > limit) {
        return true;
      }
    }
    return false;
  }

  public Enumerator<Object> unsortedEnumerator(final int numScanners,
      final BlockingQueue<CalciteScannerMessage<CalciteRow>> messages) {
    return new Enumerator<Object>() {
      private int finishedScanners = 0;
      private Object next = null;
      private boolean finished = false;
      private int totalMoves = 0;
      private boolean movedToOffset = false;

      private void moveToOffset() {
        movedToOffset = true;
        if (offset > 0) {
          while(totalMoves < offset && moveNext());
        }
      }

      @Override
      public boolean moveNext() {
        if (finished) {
          return false;
        }
        if (!movedToOffset) {
          moveToOffset();
        }
        CalciteScannerMessage<CalciteRow> fetched;
        do {
          try {
            fetched = messages.poll(350, TimeUnit.MILLISECONDS);
          }
          catch (InterruptedException interrupted) {
            fetched = CalciteScannerMessage.createEndMessage();
          }
          if (fetched != null) {
            if (fetched.type == CalciteScannerMessage.MessageType.ERROR) {
              throw new RuntimeException("A scanner failed, failing whole query", fetched.failure.get());
            }
            if (fetched.type == CalciteScannerMessage.MessageType.CLOSE) {
              if (++finishedScanners >= numScanners) {
                finished = true;
                return false;
              }
            }
          }

        } while(fetched == null ||
            fetched.type != CalciteScannerMessage.MessageType.ROW);
        // Indicates this is the first move.
        if (next == null) {
          scanStats.setFirstRow();
        }
        next = fetched.row.get().getRowData();
        totalMoves++;
        boolean limitReached = checkLimitReached(totalMoves);
        if (limitReached) {
          scansShouldStop.set(true);
        }
        return !limitReached;
      }

      @Override
      public Object current() {
        return next;
      }

      @Override
      public void reset() {
        throw new RuntimeException("Cannot reset an UnsortedEnumerable");
      }

      @Override
      public void close() {
        scansShouldStop.set(true);
        scanStats.setTotalTime();
      }
    };
  }

  public Enumerator<Object> sortedEnumerator(final List<Enumerator<CalciteRow>> subEnumerables) {

    return new Enumerator<Object>() {
      private Object next = null;
      private List<Boolean> enumerablesWithRows = new ArrayList<>(subEnumerables.size());
      private int totalMoves = 0;

      private void moveToOffset() {
        // handling of limit and/or offset for groupBySorted is done in the groupBy method
        if (offset > 0 && !groupBySorted) {
          while(totalMoves < offset && moveNext());
        }
      }

      @Override
      public boolean moveNext() {
        // @TODO: is possible for subEnumerables to be empty?
        if (subEnumerables.isEmpty()) {
          return false;
        }

        if (enumerablesWithRows.isEmpty()) {
          for (int idx = 0; idx < subEnumerables.size(); idx++) {
            enumerablesWithRows.add(subEnumerables.get(idx).moveNext());
          }
          moveToOffset();
          logger.debug("Setup scanners {}", enumerablesWithRows);
        }
        CalciteRow smallest = null;
        int chosenEnumerable = -1;
        for (int idx = 0; idx < subEnumerables.size(); idx++) {
          if (enumerablesWithRows.get(idx)) {
            final CalciteRow enumerablesNext = subEnumerables.get(idx).current();
            if (smallest == null) {
              logger.trace("smallest isn't set setting to {}", enumerablesNext.getRowData());
              smallest = enumerablesNext;
              chosenEnumerable = idx;
            }
            else if (enumerablesNext.compareTo(smallest) < 0) {
              logger.trace("{} is smaller then {}",
                  enumerablesNext.getRowData(), smallest.getRowData());
              smallest = enumerablesNext;
              chosenEnumerable = idx;
            }
            else {
              logger.trace("{} is larger then {}",
                  enumerablesNext.getRowData(), smallest.getRowData());
            }
          }
          else {
            logger.trace("{} index doesn't have next", idx);
          }
        }
        if (smallest == null) {
          return false;
        }
        // Indicates this is the first move.
        if (next == null) {
          scanStats.setFirstRow();
        }
        scanStats.incrementRowCount(1L);
        next = smallest.getRowData();

        // Move the chosen one forward. The others have their smallest
        // already in the front of their queues.
        logger.trace("Chosen idx {} to move next", chosenEnumerable);
        enumerablesWithRows.set(chosenEnumerable,
            subEnumerables.get(chosenEnumerable).moveNext());
        totalMoves++;
        boolean limitReached = checkLimitReached(totalMoves);

        if (limitReached) {
          scansShouldStop.set(true);
        }
        return !limitReached;
      }

      @Override
      public Object current() {
        return next;
      }

      @Override
      public void reset() {
        subEnumerables
          .stream()
          .forEach(e -> e.reset());
      }

      @Override
      public void close() {
        subEnumerables.stream()
          .forEach(enumerable -> enumerable.close());
        scanStats.setTotalTime();
      }
    };
  }

  @Override
  public Enumerator<Object> enumerator() {
    if (sort) {
      return sortedEnumerator(
          scanners
          .stream()
          .map(scanner -> {
                final BlockingQueue<CalciteScannerMessage<CalciteRow>> rowResults = new LinkedBlockingQueue<>();

                // Yuck!!! side effect within a mapper. This is because the
                // callback and the CalciteKuduEnumerable need to both share
                // queue.
                scanner.nextRows()
                  .addBothDeferring(
                      new ScannerCallback(scanner,
                          rowResults,
                          scansShouldStop,
                          tableSchema,
                          projectedSchema,
                          descendingSortedFieldIndices,
                          scanStats));
                // Limit is not required here. do not use it.
                return new CalciteKuduEnumerable(
                    rowResults,
                    scansShouldStop
                );
              }
          )
          .map(enumerable -> enumerable.enumerator())
          .collect(Collectors.toList()));
    }
    final BlockingQueue<CalciteScannerMessage<CalciteRow>> messages = new LinkedBlockingQueue<>();
    scanners
      .stream()
      .forEach(scanner -> {
            scanner.nextRows()
              .addBothDeferring(
                  new ScannerCallback(scanner,
                      messages,
                      scansShouldStop,
                      tableSchema,
                      projectedSchema,
                      descendingSortedFieldIndices,
                      scanStats));
          });
    final int numScanners = scanners.size();

    return unsortedEnumerator(numScanners, messages);
  }

  @Override
  public <TKey, TAccumulate, TResult> Enumerable<TResult> groupBy(
      Function1<Object, TKey> keySelector,
      Function0<TAccumulate> accumulatorInitializer,
      Function2<TAccumulate, Object, TAccumulate> accumulatorAdder,
      Function2<TKey, TAccumulate, TResult> resultSelector) {
    // When Grouping rows but the aggregation is not sorted by primary key direction or there is no
    // limit to the grouping, read every single matching row for this query.
    // This implies sorted = false.
    if (!groupBySorted) {
      return EnumerableDefaults.groupBy(getThis(), keySelector,
          accumulatorInitializer, accumulatorAdder, resultSelector);
    }

    int uniqueGroupCount = 0;
    TKey lastKey = null;

    // groupFetchLimit calculates it's size based on offset. When offset is present, it needs to
    // skip an equalivent  number of unique group keys
    long groupFetchLimit = Long.MAX_VALUE;
    if (offset > 0 && limit>0) {
      groupFetchLimit = limit + offset;
    }
    else if (offset > 0) {
      groupFetchLimit = offset;
    }
    else if (limit > 0) {
      groupFetchLimit = limit;
    }
    final Queue<TResult> sortedResults = new LinkedList<TResult>();

    try (Enumerator<Object> objectEnumeration = getThis().enumerator()) {
      TAccumulate accumulator = null;

      while (objectEnumeration.moveNext()) {
        Object o = objectEnumeration.current();
        final TKey key = keySelector.apply(o);

        // If there hasn't been a key yet or if there is a new key
        if (lastKey == null || !key.equals(lastKey)) {
          // If there is an accumulator, save the results into the queue and reset accumulator.
          if (accumulator != null) {
            sortedResults.offer(resultSelector.apply(lastKey, accumulator));
            accumulator = null;
          }

          uniqueGroupCount++;

          // When we have seen limit + 1 unique group by keys, exit.
          // or in the case of an offset, limit + offset + 1 unique group by keys.
          if (uniqueGroupCount > groupFetchLimit) {
            break;
          }

          lastKey = key;
        }

        // When we are still skipping group by keys.
        if (offset > 0 && uniqueGroupCount <= offset) {
          continue;
        }

        // First Kudu record matching key. Init the accumulator function.
        if (accumulator == null) {
          accumulator = accumulatorInitializer.apply();
        }
        accumulator = accumulatorAdder.apply(accumulator, o);
      }

      // If the source Enumerator -- objectEnumeration -- runs out of rows and we have an accumulator in progress
      // Apply it and save it.
      if (accumulator != null) {
        sortedResults.offer(resultSelector.apply(lastKey, accumulator));
      }
    }
    return new AbstractEnumerable2<TResult>() {
      @Override
      public Iterator<TResult> iterator() {
        return sortedResults.iterator();
      }
    };
  }
}
