from pyupbit_api import PyUpbitAPI

class AssetCalculator:
    def __init__(self, access_key, secret_key):
        self.api = PyUpbitAPI(access_key, secret_key)

    def get_total_asset(self):
        """Calculate the total assets (cash + total coin purchase amount)."""
        cash_balance = self.api.get_balance()
        total_coin_purchase_amount = 0.0

        # Get balance of all coins
        balances = self.api.upbit.get_balances()
        for balance in balances:
            if balance['currency'] != 'KRW':
                coin = 'KRW-' + balance['currency']
                coin_balance = float(balance['balance'])
                avg_buy_price = float(balance['avg_buy_price'])
                total_coin_purchase_amount += coin_balance * avg_buy_price

        total_asset = cash_balance + total_coin_purchase_amount
        return total_asset

    def get_cash_ratio(self):
        """Calculate the cash ratio (cash / total asset * 100)."""
        cash_balance = self.api.get_balance()
        total_asset = self.get_total_asset()

        if total_asset == 0:
            return 0.0

        cash_ratio = (cash_balance / total_asset) * 100.0
        return cash_ratio

    def get_balance(self):
        """Get the current balance of cash (KRW)."""
        return self.api.get_balance()

    def get_coin_balance(self, coin):
        """Get the balance of a specific coin."""
        return self.api.get_coin_balance(coin)

    def get_current_price(self, coin):
        """Get the current price of a specific coin."""
        return self.api.get_current_price(coin)

    def get_avg_buy_price(self, coin):
        """Get the average buy price of a specific coin."""
        return self.api.get_avg_buy_price(coin)

    def sell_market_order(self, coin, amount):
        """Sell a specific amount of a coin at market price."""
        return self.api.sell_market_order(coin, amount)

    def buy_limit_order(self, coin, price, amount):
        """Buy a specific amount of a coin at a limit price."""
        return self.api.buy_limit_order(coin, price, amount)
