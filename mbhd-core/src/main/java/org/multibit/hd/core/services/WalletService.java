package org.multibit.hd.core.services;

import com.google.bitcoin.core.*;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.multibit.hd.core.dto.*;
import org.multibit.hd.core.exceptions.PaymentsLoadException;
import org.multibit.hd.core.exceptions.PaymentsSaveException;
import org.multibit.hd.core.managers.InstallationManager;
import org.multibit.hd.core.managers.WalletManager;
import org.multibit.hd.core.store.Payments;
import org.multibit.hd.core.store.PaymentsProtobufSerializer;
import org.multibit.hd.core.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Date;
import java.util.Set;

/**
 *  <p>Service to provide the following to GUI classes:<br>
 *  <ul>
 *  <li>list Transactions in the current wallet</li>
 *  </ul>
 * <p/>
 * Most of the functionality is provided by WalletManager and BackupManager.
 * This service provides a single point of entry for GUI code and encapsulates things in a service thread. 
 */
public class WalletService extends AbstractService {

  /**
   * The name of the directory (within the wallet directory) that contains the payments database
   */
  public final static String PAYMENTS_DIRECTORY_NAME = "payments";

  /**
   * The name of the protobuf file containing additional payments information
   */
  public static final String PAYMENTS_DATABASE_NAME = "payments.db";

  /**
   * The location of the backing writeContacts for the payments
   */
  private File backingStoreFile;

  /**
   * The serializer for the backing writeContacts
   */
  private PaymentsProtobufSerializer protobufSerializer;

  /**
   * The payments containing payment requests and transaction infos
   */
  private Payments payments;

  /**
   * The wallet id that this WalletService is using
   */
  private WalletId walletId;

  @Override
  public void start() {
    this.requireSingleThreadExecutor();
  }

  /**
   * Initialise the wallet service with a wallet id so that it knows where to put files etc
   *
   * @param walletId the walletId to use for this WalletService
   */
  public void initialise(WalletId walletId) {
    Preconditions.checkNotNull(walletId, "'walletId' must be present");

    this.walletId = walletId;

    // Work out where to writeContacts the contacts for this wallet id.
    File applicationDataDirectory = InstallationManager.getOrCreateApplicationDataDirectory();
    String walletRoot = WalletManager.createWalletRoot(walletId);

    File walletDirectory = WalletManager.getWalletDirectory(applicationDataDirectory.getAbsolutePath(), walletRoot);

    File paymentsDirectory = new File(walletDirectory.getAbsolutePath() + File.separator + PAYMENTS_DIRECTORY_NAME);
    FileUtils.createDirectoryIfNecessary(paymentsDirectory);

    this.backingStoreFile = new File(paymentsDirectory.getAbsolutePath() + File.separator + PAYMENTS_DATABASE_NAME);


    protobufSerializer = new PaymentsProtobufSerializer();

    // Load the payments data from the backing store if it exists
    if (backingStoreFile.exists()) {
      readPayments();
    }
  }

  /**
   * Get all the transactions in the current wallet
   */
  public Set<TransactionData> getTransactions() {
    // See if there is a current wallet
    WalletManager walletManager = WalletManager.INSTANCE;

    Optional<WalletData> walletDataOptional = walletManager.getCurrentWalletData();
    if (!walletDataOptional.isPresent()) {
      // No wallet is present
      return Sets.newHashSet();
    }

    // Wallet is present
    WalletData walletData = walletDataOptional.get();
    Wallet wallet = walletData.getWallet();

    // There should be a wallet
    Preconditions.checkNotNull(wallet);

    Set<Transaction> transactions = wallet.getTransactions(true);

    // Adapted transaction data to return
    Set<TransactionData> transactionDatas = Sets.newHashSet();

    if (transactions != null) {
      for (Transaction transaction : transactions) {
        TransactionData transactionData = adaptTransaction(wallet, transaction);
        transactionDatas.add(transactionData);
      }
    }
    return transactionDatas;
  }

  /**
   * Adapt a bitcoinj transaction to a TransactionData DTO
   *
   * @param wallet      the current wallet
   * @param transaction the transaction to adapt
   * @return TransactionData the transaction data
   */
  public static TransactionData adaptTransaction(Wallet wallet, Transaction transaction) {
    String transactionId = transaction.getHashAsString();
    Date updateTime = transaction.getUpdateTime();
    BigInteger amountBTC = transaction.getValue(wallet);

    TransactionConfidence transactionConfidence = transaction.getConfidence();

    int depth = 0; // By default not in a block
    TransactionConfidence.ConfidenceType confidenceType = TransactionConfidence.ConfidenceType.UNKNOWN; // By default do not know the confidence

    if (transactionConfidence != null) {
      confidenceType = transaction.getConfidence().getConfidenceType();
      if (TransactionConfidence.ConfidenceType.BUILDING.equals(confidenceType)) {
        depth = transaction.getConfidence().getDepthInBlocks();
      }
    }

    RAGStatus status = calculateStatus(transaction);

    TransactionType transactionType;
    if (amountBTC.compareTo(BigInteger.ZERO) < 0) {
      // Debit
      if (depth == 0) {
        transactionType = TransactionType.SENDING;
      } else {
        transactionType = TransactionType.SENT;
      }
    } else {
      // Credit
      if (depth == 0) {
        transactionType = TransactionType.RECEIVING;
      } else {
        transactionType = TransactionType.RECEIVED;
      }
      // TODO - requested
    }
    // TODO- fee on send

    String description;

    if (transactionType == TransactionType.RECEIVING || transactionType == TransactionType.RECEIVED) {
      description = "by"; // TODO localise
      if (transaction.getOutputs() != null) {
        for (TransactionOutput transactionOutput : transaction.getOutputs()) {
          if (transactionOutput.isMine(wallet)) {
            description = description + " " + transactionOutput.getScriptPubKey().getToAddress(NetworkParameters.fromID(NetworkParameters.ID_MAINNET));
          }
        }
      }
    } else {
      // Sent
      description = "to"; // TODO localise
      if (transaction.getOutputs() != null) {
        for (TransactionOutput transactionOutput : transaction.getOutputs()) {
          description = description + " " + transactionOutput.getScriptPubKey().getToAddress(NetworkParameters.fromID(NetworkParameters.ID_MAINNET));
        }
      }
    }

    TransactionData transactionData = new TransactionData(transactionId, updateTime, status, amountBTC, Optional.<BigInteger>absent(), confidenceType, transactionType, depth, description);

    return transactionData;
  }

  /**
   * Calculate the RAGstatus of the transaction:
   * + RED   = tx is dead, double spend, failed to be transmitted to the network
   * + AMBER = tx is unconfirmed
   * + GREEN = tx is confirmed
   *
   * @param transaction
   * @return status of the transaction
   */
  private static RAGStatus calculateStatus(Transaction transaction) {
    if (transaction.getConfidence() != null) {
      TransactionConfidence.ConfidenceType confidenceType = transaction.getConfidence().getConfidenceType();

      if (TransactionConfidence.ConfidenceType.BUILDING.equals(confidenceType)) {
        // Confirmed
        return RAGStatus.GREEN;
      } else if (TransactionConfidence.ConfidenceType.PENDING.equals(confidenceType)) {
        if (transaction.getConfidence().numBroadcastPeers() >= 2) {
          // Seen by the network but not confirmed yet
          return RAGStatus.AMBER;
        } else {
          // Not out in the network
          return RAGStatus.RED;
        }
      } else if (TransactionConfidence.ConfidenceType.DEAD.equals(confidenceType)) {
        // Dead
        return RAGStatus.RED;
      } else if (TransactionConfidence.ConfidenceType.UNKNOWN.equals(confidenceType)) {
        // Unknown
        return RAGStatus.AMBER;
      }
    } else {
      // No transaction status - don't know
      return RAGStatus.AMBER;
    }
    return RAGStatus.AMBER;
  }

  /**
   * <p>Populate the internal cache of Payments from the backing store</p>
   */
  public void readPayments() throws PaymentsLoadException {
    try (FileInputStream fis = new FileInputStream(backingStoreFile)) {

      payments = protobufSerializer.readPayments(fis);

    } catch (IOException | PaymentsLoadException e) {
      throw new PaymentsLoadException("Could not loadContacts payments db '" + backingStoreFile.getAbsolutePath() + "'. Error was '" + e.getMessage() + "'.");
    }
  }


  /**
   * <p>Save the payments data to the backing store</p>
   */
  public void writePayments() throws PaymentsSaveException {
    try (FileOutputStream fos = new FileOutputStream(backingStoreFile)) {

      protobufSerializer.writePayments(payments, fos);

    } catch (IOException | PaymentsSaveException e) {
      throw new PaymentsSaveException("Could not save payments db '" + backingStoreFile.getAbsolutePath() + "'. Error was '" + e.getMessage() + "'.");
    }
  }

  public WalletId getWalletId() {
    return walletId;
  }

}
