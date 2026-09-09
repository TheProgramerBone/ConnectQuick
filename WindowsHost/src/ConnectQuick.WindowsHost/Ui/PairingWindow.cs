using ConnectQuick.WindowsHost.Pairing;
using QRCoder;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>Shows the pairing QR (Protocol §5) for the Android Controller to scan, plus a live countdown to its 60s expiry.</summary>
public sealed class PairingWindow : Form
{
    private readonly PictureBox _qrBox;
    private readonly Label _statusLabel;
    private readonly System.Windows.Forms.Timer _countdownTimer;
    private DateTimeOffset _expiresAt;

    public PairingWindow(QrPairingPayload payload)
    {
        Text = "ConnectQuick — Vincular nuevo dispositivo";
        Width = 360;
        Height = 460;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;

        var titleLabel = new Label
        {
            Text = "Escanea este código con la app de Android",
            AutoSize = false,
            TextAlign = System.Drawing.ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 40,
        };

        _qrBox = new PictureBox
        {
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Top,
            Height = 320,
        };

        _statusLabel = new Label
        {
            AutoSize = false,
            TextAlign = System.Drawing.ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 40,
        };

        Controls.Add(_statusLabel);
        Controls.Add(_qrBox);
        Controls.Add(titleLabel);

        _countdownTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _countdownTimer.Tick += (_, _) => UpdateCountdown();
        _countdownTimer.Start();
        FormClosed += (_, _) => _countdownTimer.Dispose();

        Render(payload);
    }

    public void Render(QrPairingPayload payload)
    {
        _expiresAt = DateTimeOffset.FromUnixTimeSeconds(payload.Exp);

        var generator = new QRCodeGenerator();
        var data = generator.CreateQrCode(payload.ToJson(), QRCodeGenerator.ECCLevel.M);
        var qr = new QRCode(data);
        _qrBox.Image?.Dispose();
        _qrBox.Image = qr.GetGraphic(8);

        UpdateCountdown();
    }

    private void UpdateCountdown()
    {
        var remaining = _expiresAt - DateTimeOffset.UtcNow;
        if (remaining <= TimeSpan.Zero)
        {
            _statusLabel.Text = "Código caducado — vuelve a generar uno nuevo";
            _countdownTimer.Stop();
        }
        else
        {
            _statusLabel.Text = $"Caduca en {remaining.Seconds}s";
        }
    }
}
