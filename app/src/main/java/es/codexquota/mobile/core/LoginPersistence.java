package es.codexquota.mobile.core;

interface LoginPersistence {
    void clearPendingChallenge();

    boolean completeLogin(TokenBundle tokenBundle, DeviceLogin.Challenge challenge) throws ApiException;

    long generation();

    DeviceLogin.Challenge loadPendingChallenge();

    boolean markExchangeStarted(DeviceLogin.Challenge challenge) throws ApiException;

    boolean resetUnsentExchange(DeviceLogin.Challenge challenge) throws ApiException;

    void savePendingChallenge(DeviceLogin.Challenge challenge) throws ApiException;
}
