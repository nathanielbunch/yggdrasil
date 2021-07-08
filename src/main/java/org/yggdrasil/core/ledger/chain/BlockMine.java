package org.yggdrasil.core.ledger.chain;

import org.apache.commons.lang3.ArrayUtils;
import org.openjdk.jol.info.GraphLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yggdrasil.core.ledger.Mempool;
import org.yggdrasil.core.ledger.transaction.Transaction;
import org.yggdrasil.core.ledger.wallet.WalletIndexer;
import org.yggdrasil.core.utils.CryptoHasher;
import org.yggdrasil.node.network.NodeConfig;
import org.yggdrasil.node.network.messages.Message;
import org.yggdrasil.node.network.messages.Messenger;
import org.yggdrasil.node.network.messages.enums.RequestType;
import org.yggdrasil.node.network.messages.payloads.BlockMessage;
import org.yggdrasil.node.network.messages.payloads.TransactionPayload;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BlockMine {

    private final Logger logger = LoggerFactory.getLogger(BlockMine.class);
    private final Integer _MAX_BLOCK_SIZE = 2048;

    @Autowired
    private NodeConfig nodeConfig;
    @Autowired
    private Messenger messenger;
    @Autowired
    private Mempool mempool;
    @Autowired
    private Blockchain blockchain;
    @Autowired
    private WalletIndexer walletIndexer;

    private Thread miningThread;
    protected boolean isMiningState = false;

    @PostConstruct
    private void init() {
        this.isMiningState = false;
    }

    public void startMining() {
        if(this.miningThread.isAlive()) {
            this.isMiningState = false;
        }
        // erase old thread
        this.miningThread = null;
        // make a new thread with the runner to mine
        this.miningThread = new Thread();
        this.isMiningState = true;
        this.miningThread.start();
    }

    public void stopMining() {
        // stop the thread for mining and destroy it
        if(this.miningThread.isAlive()){
            this.isMiningState = false;
        }
    }

    // method that will be called by the runner.
    public void mineBlocks() throws Exception {
        logger.info("Mining new block...");
        // make a blocking check here for memTxns size > 10.
        List<Transaction> memTxns = this.mempool.getTransaction(_MAX_BLOCK_SIZE);
        // The txns selected to be in this block.
        Set<Transaction> bTxnCandidates;
        // need to check what are the most valuable transactions and perform work on those
        // with some free transactions, a maximum of 10% of total work.
        logger.info("Selecting transactions to be included in the new block.");
        memTxns.sort(Comparator.comparing(Transaction::getValue));
        BigDecimal mV = memTxns.stream().map(Transaction::getValue).reduce(BigDecimal::add).orElse(BigDecimal.ONE).divide(BigDecimal.valueOf(memTxns.size()), RoundingMode.HALF_UP);
        logger.info("Median value of {} transactions to be evaluated: {}", memTxns.size(), mV);
        bTxnCandidates = memTxns.stream().filter(memTxnF -> memTxnF.getValue().compareTo(mV) > 0).collect(Collectors.toSet());
        int tenPercent = (int) Math.round(1.0*memTxns.size()*0.1);
        logger.info("{} high value transactions selected, with {} low value ones to be added.", bTxnCandidates.size(), tenPercent);
        // shifting the percent and size by one to avoid one-off errors
        bTxnCandidates.addAll(memTxns.subList(memTxns.size()-(tenPercent+1), memTxns.size()-1));
        logger.info("New block will contain {} total txns.", bTxnCandidates.size());
        // Generate a coinbase transaction to be included in the block
        // verify that only one can be added to the block

        // Get the last known block to reference in the new block
        Block lastBlock = this.blockchain.getLastBlock().orElse(null);
        // Transaction payloiad for including in the block message
        List<TransactionPayload> txnMessagePayloads = new ArrayList<>();
        for(Transaction txn : bTxnCandidates) {
            // Validate every txn

            // Once the txn is validated, add to the transactionPayload
            TransactionPayload txnP = TransactionPayload.Builder.newBuilder()
                    .buildFromTransaction(txn)
                    .build();
            txnMessagePayloads.add(txnP);
        }
        // Merkle root variable for including in the block
        byte[] merkleRoot = generateMerkleTree(new ArrayList<>(bTxnCandidates));
        // Compile the block
        Block newBlock = Block.Builder.newBuilder()
                .setBlockHeight(lastBlock.getBlockHeight().add(BigInteger.ONE))
                .setPreviousBlock(lastBlock.getBlockHash())
                .setData(new ArrayList<>(bTxnCandidates))
                .setMerkleRoot(merkleRoot)
                .build();
        // Perform the proof of work
        this.walletIndexer.getCurrentWallet().signBlock(newBlock);
        newBlock = this.proofOfWork(newBlock, this.blockchain.calculateDifficulty());
        // add the block to the blockchain
        this.blockchain.addBlock(newBlock);
        memTxns.removeAll(bTxnCandidates);
        logger.debug("Dumping low-value transactions back into the mempool for later block.");
        this.mempool.putAllTransaction(memTxns);
        logger.info("Added new block to the chain: {}", newBlock);
        // make the transaction list
        BlockMessage blockMessage = BlockMessage.Builder.newBuilder()
                .setTimestamp((int) newBlock.getTimestamp().toEpochSecond())
                .setBlockHeight(newBlock.getBlockHeight())
                .setBlockHash(newBlock.getBlockHash())
                .setPreviousBlockHash(newBlock.getPreviousBlockHash())
                .setTxnPayloads(txnMessagePayloads.toArray(TransactionPayload[]::new))
                .setMerkleRoot(newBlock.getMerkleRoot())
                .setSignature(newBlock.getSignature())
                .build();
        Message message = Message.Builder.newBuilder()
                .setNetwork(nodeConfig.getNetwork())
                .setRequestType(RequestType.DATA_RESP)
                .setMessagePayload(blockMessage)
                .setPayloadSize(BigInteger.valueOf(GraphLayout.parseInstance(blockMessage).totalSize()))
                .setChecksum(CryptoHasher.hash(blockMessage))
                .build();
        this.messenger.sendBroadcastMessage(message);
        logger.debug("New block {} has been forwarded to other nodes.", newBlock);
    }

    private byte[] generateMerkleTree(List<Transaction> txns) throws NoSuchAlgorithmException {
        if(txns.size()%2 != 0) {
            txns.add(txns.get(txns.size()-1));
        }
        byte[] temp = new byte[0];
        if(txns.size() == 2) {
            temp = appendBytes(temp, txns.get(0).getTxnHash());
            temp = appendBytes(temp, txns.get(1).getTxnHash());
            return CryptoHasher.dhash(temp);
        }
        if(txns.size() == 1) {
            temp = appendBytes(temp, txns.get(0).getTxnHash());
            temp = appendBytes(temp, txns.get(0).getTxnHash());
            return CryptoHasher.dhash(temp);
        }
        // pass first 1/2 and second 1/2
        return CryptoHasher.dhash(appendBytes(generateMerkleTree(txns.subList(0, (txns.size()/2)-1)), generateMerkleTree(txns.subList((txns.size()/2), txns.size()-1))));
    }

    private Block proofOfWork(Block currentBlock, int difficulty) throws Exception {
        List<Transaction> blockTransactions = currentBlock.getData();
        blockTransactions.sort(Comparator.comparing(Transaction::getTimestamp));
        blockTransactions.sort(Comparator.comparing(Transaction::isCoinbase));
        Block sortedBlock = Block.Builder.newBuilder()
                .setPreviousBlock(currentBlock.getPreviousBlockHash())
                .setBlockHeight(currentBlock.getBlockHeight())
                .setMerkleRoot(currentBlock.getMerkleRoot())
                .setData(blockTransactions)
                .build();
        logger.info("Initial new block hash value: {}", this.sumBytes(sortedBlock.getBlockHash()));
        logger.info("Trying to beat difficulty: {}", difficulty);
        String prefixString = new String(new char[difficulty]).replace('\0', '0');
        while (!sortedBlock.toString().substring(0, difficulty).equals(prefixString)) {
            sortedBlock.incrementNonce();
            sortedBlock.setBlockHash(CryptoHasher.hash(sortedBlock));
        }
        return sortedBlock;
    }

    private int sumBytes(byte[] bytes) {
        int sum = 0;
        for(byte b : bytes) {
            sum += b;
        }
        return sum;
    }

    private static byte[] appendBytes(byte[] base, byte[] extension) {
        return ArrayUtils.addAll(base, extension);
    }

}
