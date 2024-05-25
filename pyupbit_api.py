import pyupbit

class PyUpbitAPI:
    def __init__(self, access_key, secret_key):
        self.upbit = pyupbit.Upbit(access_key, secret_key)

    def get_balance(self):
        """Get the current balance of cash (KRW)."""
        return self.upbit.get_balance()

    def get_coin_balance(self, coin):
        """Get the balance of a specific coin."""
        return self.upbit.get_balance(coin)

    def get_current_price(self, coin):
        """Get the current price of a specific coin."""
        return pyupbit.get_current_price(coin)

    def get_avg_buy_price(self, coin):
        """Get the average buy price of a specific coin."""
        balance = self.upbit.get_balances()
        for b in balance:
            if b['currency'] == coin:
                return float(b['avg_buy_price'])
        return 0

    def sell_market_order(self, coin, amount):
        """Sell a specific amount of a coin at market price."""
        return self.upbit.sell_market_order(coin, amount)

    def buy_limit_order(self, coin, price, amount):
        """Buy a specific amount of a coin at a limit price."""
        return self.upbit.buy_limit_order(coin, price, amount)

# Example usage
if __name__ == "__main__":
    access_key = "your-access-key"
    secret_key = "your-secret-key"
    
    api = PyUpbitAPI(access_key, secret_key)
    
    cash_balance = api.get_balance()
    print(f"Cash Balance: {cash_balance}")

    coin = "KRW-BTC"
    coin_balance = api.get_coin_balance(coin)
    print(f"{coin} Balance: {coin_balance}")

    current_price = api.get_current_price(coin)
    print(f"{coin} Current Price: {current_price}")

    avg_buy_price = api.get_avg_buy_price(coin)
    print(f"{coin} Average Buy Price: {avg_buy_price}")

    # Limit buy example
    buy_result = api.buy_limit_order(coin, current_price - 100000, 0.0005)
    print(f"Limit Buy Result: {buy_result}")

    # Market sell example
    sell_result = api.sell_market_order(coin, 0.0005)
    print(f"Market Sell Result: {sell_result}")
