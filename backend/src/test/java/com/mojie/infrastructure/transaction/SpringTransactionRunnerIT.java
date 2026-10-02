package com.mojie.infrastructure.transaction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.common.error.BusinessException;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.support.*;
import org.junit.jupiter.api.Test;

class SpringTransactionRunnerIT {
  @Test
  void requiredParticipantsRollbackTogetherAndNewTransactionsCommitIndependently()
      throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var tx = TestServices.transactions(database.source());
      var db = new JdbcAccess(database.source());
      db.update("CREATE TABLE tx_check(id int PRIMARY KEY, value int)");
      assertThrows(
          BusinessException.class,
          () ->
              tx.required(
                  () -> {
                    db.update("INSERT INTO tx_check VALUES (1, 10)");
                    tx.required(
                        () -> {
                          db.update("INSERT INTO tx_check VALUES (2, 20)");
                          return null;
                        });
                    throw new BusinessException("VALIDATION_ERROR", "Reject the entire save");
                  }));
      assertTrue(db.query("SELECT * FROM tx_check").isEmpty());
      assertThrows(
          BusinessException.class,
          () ->
              tx.required(
                  () -> {
                    db.update("INSERT INTO tx_check VALUES (1, 10)");
                    tx.requiresNew(
                        () -> {
                          db.update("INSERT INTO tx_check VALUES (2, 20)");
                          return null;
                        });
                    throw new BusinessException("VALIDATION_ERROR", "Rollback the outer write");
                  }));
      assertEquals(2, db.first("SELECT id FROM tx_check").get("id"));
      assertEquals(1, db.query("SELECT * FROM tx_check").size());
    }
  }

  @Test
  void snapshotIsReadOnlyRepeatableReadEvenInsideReadCommitted() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var tx = TestServices.transactions(database.source());
      var db = new JdbcAccess(database.source());
      db.update("CREATE TABLE tx_check(id int PRIMARY KEY, value int)");
      db.update("INSERT INTO tx_check VALUES (1, 10)");
      tx.required(
          () -> {
            assertEquals(
                "read committed",
                db.first("SHOW transaction_isolation").get("transactionIsolation"));
            tx.readSnapshot(
                () -> {
                  assertEquals(
                      "repeatable read",
                      db.first("SHOW transaction_isolation").get("transactionIsolation"));
                  assertEquals(
                      "on", db.first("SHOW transaction_read_only").get("transactionReadOnly"));
                  assertEquals(10, db.first("SELECT value FROM tx_check").get("value"));
                  tx.requiresNew(
                      () -> {
                        db.update("UPDATE tx_check SET value = 20");
                        return null;
                      });
                  assertEquals(10, db.first("SELECT value FROM tx_check").get("value"));
                  return null;
                });
            return null;
          });
      assertEquals(20, db.first("SELECT value FROM tx_check").get("value"));
    }
  }
}
