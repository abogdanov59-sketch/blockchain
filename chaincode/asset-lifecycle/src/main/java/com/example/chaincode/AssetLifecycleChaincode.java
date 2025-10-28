package com.example.chaincode;

import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Info;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.contract.Context;

@Contract(name = "AssetLifecycle", info = @Info(title = "Asset Lifecycle", description = "MVP placeholder chaincode for asset transactions"))
@Default
public class AssetLifecycleChaincode {

    @Transaction
    public String createOrder(Context ctx, String orderId, String assetId, String metadataHash) {
        return String.format("createOrder invoked: orderId=%s assetId=%s hash=%s", orderId, assetId, metadataHash);
    }
}
