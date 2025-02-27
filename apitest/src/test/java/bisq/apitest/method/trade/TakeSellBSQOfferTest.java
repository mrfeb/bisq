/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.apitest.method.trade;

import io.grpc.StatusRuntimeException;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.config.ApiTestConfig.BSQ;
import static bisq.apitest.config.ApiTestConfig.BTC;
import static bisq.cli.table.builder.TableType.OFFER_TBL;
import static bisq.core.trade.model.bisq_v1.Trade.Phase.PAYOUT_PUBLISHED;
import static bisq.core.trade.model.bisq_v1.Trade.Phase.WITHDRAWN;
import static bisq.core.trade.model.bisq_v1.Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG;
import static bisq.core.trade.model.bisq_v1.Trade.State.WITHDRAW_COMPLETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static protobuf.OfferDirection.BUY;



import bisq.apitest.method.offer.AbstractOfferTest;
import bisq.cli.table.builder.TableBuilder;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TakeSellBSQOfferTest extends AbstractTradeTest {

    // Alice is maker / bsq seller (btc buyer), Bob is taker / bsq buyer (btc seller).

    // Maker and Taker fees are in BTC.
    private static final String TRADE_FEE_CURRENCY_CODE = BTC;

    private static final String WITHDRAWAL_TX_MEMO = "Bob's trade withdrawal";

    @BeforeAll
    public static void setUp() {
        AbstractOfferTest.setUp();
        EXPECTED_PROTOCOL_STATUS.init();
    }

    @Test
    @Order(1)
    public void testTakeAlicesBuyBTCForBSQOffer(final TestInfo testInfo) {
        try {
            // Alice is going to SELL BSQ, but the Offer direction = BUY because it is a
            // BTC trade;  Alice will BUY BTC for BSQ.  Alice will send Bob BSQ.
            // Confused me, but just need to remember there are only BTC offers.
            var btcTradeDirection = BUY.name();
            var alicesOffer = aliceClient.createFixedPricedOffer(btcTradeDirection,
                    BSQ,
                    15_000_000L,
                    7_500_000L,
                    "0.000035",   // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
                    defaultBuyerSecurityDepositPct.get(),
                    alicesLegacyBsqAcct.getId(),
                    TRADE_FEE_CURRENCY_CODE);
            log.debug("Alice's SELL BSQ (BUY BTC) Offer:\n{}", new TableBuilder(OFFER_TBL, alicesOffer).build());
            genBtcBlocksThenWait(1, 4_000);
            var offerId = alicesOffer.getId();
            assertTrue(alicesOffer.getIsCurrencyForMakerFeeBtc());
            var alicesBsqOffers = aliceClient.getMyOffers(btcTradeDirection, BSQ);
            assertEquals(1, alicesBsqOffers.size());
            var trade = takeAlicesOffer(offerId,
                    bobsLegacyBsqAcct.getId(),
                    TRADE_FEE_CURRENCY_CODE,
                    false);
            sleep(2_500);  // Allow available offer to be removed from offer book.
            alicesBsqOffers = aliceClient.getMyOffersSortedByDate(BSQ);
            assertEquals(0, alicesBsqOffers.size());
            genBtcBlocksThenWait(1, 2_500);
            waitForDepositConfirmation(log, testInfo, bobClient, trade.getTradeId());
            trade = bobClient.getTrade(tradeId);
            verifyTakerDepositConfirmed(trade);
            logTrade(log, testInfo, "Alice's Maker/Seller View", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(2)
    public void testAlicesConfirmPaymentStarted(final TestInfo testInfo) {
        try {
            var trade = aliceClient.getTrade(tradeId);
            waitForDepositConfirmation(log, testInfo, aliceClient, trade.getTradeId());
            sendBsqPayment(log, aliceClient, trade);
            genBtcBlocksThenWait(1, 2_500);
            aliceClient.confirmPaymentStarted(trade.getTradeId());
            sleep(6_000);
            waitForBuyerSeesPaymentInitiatedMessage(log, testInfo, aliceClient, tradeId);
            logTrade(log, testInfo, "Alice's Maker/Seller View (Payment Sent)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View (Payment Sent)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(3)
    public void testBobsConfirmPaymentReceived(final TestInfo testInfo) {
        try {
            waitForSellerSeesPaymentInitiatedMessage(log, testInfo, bobClient, tradeId);

            sleep(2_000);
            var trade = bobClient.getTrade(tradeId);
            verifyBsqPaymentHasBeenReceived(log, bobClient, trade);
            bobClient.confirmPaymentReceived(trade.getTradeId());
            sleep(3_000);
            trade = bobClient.getTrade(tradeId);
            // Warning:  trade.getOffer().getState() might be AVAILABLE, not OFFER_FEE_PAID.
            EXPECTED_PROTOCOL_STATUS.setState(SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG)
                    .setPhase(PAYOUT_PUBLISHED)
                    .setPayoutPublished(true)
                    .setPaymentReceivedMessageSent(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's Maker/Seller View (Payment Received)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View (Payment Received)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(4)
    public void testAlicesBtcWithdrawalToExternalAddress(final TestInfo testInfo) {
        try {
            genBtcBlocksThenWait(1, 1_000);

            var trade = aliceClient.getTrade(tradeId);
            logTrade(log,
                    testInfo,
                    "Alice's view before closing trade and withdrawing BTC funds to external wallet",
                    trade);
            String toAddress = bitcoinCli.getNewBtcAddress();
            aliceClient.withdrawFunds(tradeId, toAddress, WITHDRAWAL_TX_MEMO);
            // Bob closes trade and keeps funds.
            bobClient.closeTrade(tradeId);
            genBtcBlocksThenWait(1, 1_000);
            trade = aliceClient.getTrade(tradeId);
            EXPECTED_PROTOCOL_STATUS.setState(WITHDRAW_COMPLETED)
                    .setPhase(WITHDRAWN)
                    .setCompleted(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's Maker/Seller View (Done)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View (Done)", bobClient.getTrade(tradeId));
            logBalances(log, testInfo);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }
}
