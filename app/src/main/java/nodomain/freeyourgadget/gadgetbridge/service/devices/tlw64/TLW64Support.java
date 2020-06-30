/*  Copyright (C) 2015-2020 Andreas Böhler, Andreas Shimokawa, Carsten
    Pfeiffer, Daniel Dakhno, Daniele Gobbetti, JohnnySun, José Rebelo,
    Erik Bloß

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package nodomain.freeyourgadget.gadgetbridge.service.devices.tlw64;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateFormat;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.tlw64.TLW64Constants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CalendarEventSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CannedMessagesSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static org.apache.commons.lang3.math.NumberUtils.min;

public class TLW64Support extends AbstractBTLEDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TLW64Support.class);

    private final BatteryInfoProfile<TLW64Support> batteryInfoProfile;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
    public BluetoothGattCharacteristic ctrlCharacteristic = null;
    public BluetoothGattCharacteristic notifyCharacteristic = null;

    private final IntentListener mListener = new IntentListener() {
        @Override
        public void notify(Intent intent) {
            String s = intent.getAction();
            if (s.equals(BatteryInfoProfile.ACTION_BATTERY_INFO)) {
                handleBatteryInfo((nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo) intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO));
            }
        }
    };

    public TLW64Support() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(TLW64Constants.UUID_SERVICE_NO1);

        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(mListener);
        addSupportedProfile(batteryInfoProfile);
    }

    private void handleBatteryInfo(nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo info) {
        batteryCmd.level = (short) info.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        LOG.info("Initializing");

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        ctrlCharacteristic = getCharacteristic(TLW64Constants.UUID_CHARACTERISTIC_CONTROL);
        notifyCharacteristic = getCharacteristic(TLW64Constants.UUID_CHARACTERISTIC_NOTIFY);

        builder.setGattCallback(this);
        builder.notify(notifyCharacteristic, true);

        setTime(builder);
        setDisplaySettings(builder);
        sendSettings(builder);

        batteryInfoProfile.requestBatteryInfo(builder);
        builder.write(ctrlCharacteristic, new byte[]{TLW64Constants.CMD_FIRMWARE_VERSION});

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));

        LOG.info("Initialization Done");

        return builder;
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (super.onCharacteristicChanged(gatt, characteristic)) {
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();
        byte[] data = characteristic.getValue();
        if (data.length == 0)
            return true;

        switch (data[0]) {
            case TLW64Constants.CMD_DISPLAY_SETTINGS:
                LOG.info("Display settings updated");
                return true;
            case TLW64Constants.CMD_FIRMWARE_VERSION:
                // TODO: firmware version reported "RM07JV000404" but original app: "RM07JV000404_15897"
                versionCmd.fwVersion = new String(Arrays.copyOfRange(data, 1, data.length));
                handleGBDeviceEvent(versionCmd);
                LOG.info("Firmware version is: " + versionCmd.fwVersion);
                return true;
            case TLW64Constants.CMD_BATTERY:
                batteryCmd.level = data[1];
                LOG.info("Battery level is: " + data[1] + "%   This is DEPRECATED. We now use the generic battery service 0x180F");
                return true;
            case TLW64Constants.CMD_DATETIME:
                LOG.info("Time is set to: " + (data[1] * 256 + ((int) data[2] & 0xff)) + "-" + data[3] + "-" + data[4] + " " + data[5] + ":" + data[6] + ":" + data[7]);
                return true;
            case TLW64Constants.CMD_USER_DATA:
                LOG.info("User data updated");
                return true;
            case TLW64Constants.CMD_ALARM:
                LOG.info("Alarm updated");
                return true;
            case TLW64Constants.CMD_FACTORY_RESET:
                LOG.info("Factory reset requested");
                return true;
            case TLW64Constants.CMD_NOTIFICATION:
                LOG.info("Notification is displayed");
                return true;
            case TLW64Constants.CMD_ICON:
                LOG.info("Icon is displayed");
                return true;
            case TLW64Constants.CMD_DEVICE_SETTINGS:
                LOG.info("Device settings updated");
                return true;
            default:
                LOG.warn("Unhandled characteristic change: " + characteristicUUID + " code: " + Arrays.toString(data));
                return true;
        }
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        switch (notificationSpec.type) {
            case GENERIC_SMS:
                showNotification(TLW64Constants.NOTIFICATION_SMS, notificationSpec.sender);
                setVibration(1, 1);
                break;
            case WECHAT:
                showIcon(TLW64Constants.ICON_WECHAT);
                setVibration(1, 1);
                break;
            default:
                showIcon(TLW64Constants.ICON_MAIL);
                setVibration(1, 1);
                break;
        }
    }

    @Override
    public void onDeleteNotification(int id) {

    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("setTime");
            setTime(builder);
            builder.queue(getQueue());
        } catch (IOException e) {
            GB.toast(getContext(), "Error setting time: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            TransactionBuilder builder = performInitialized("Set alarm");
            boolean anyAlarmEnabled = false;
            for (Alarm alarm : alarms) {
                anyAlarmEnabled |= alarm.getEnabled();
                Calendar calendar = AlarmUtils.toCalendar(alarm);

                int maxAlarms = 3;
                if (alarm.getPosition() >= maxAlarms) {
                    if (alarm.getEnabled()) {
                        GB.toast(getContext(), "Only 3 alarms are supported.", Toast.LENGTH_LONG, GB.WARN);
                    }
                    return;
                }

                byte repetition = 0x00;

                switch (alarm.getRepetition()) {
                    // TODO: case Alarm.ALARM_ONCE is not supported! Need to notify user somehow...
                    case Alarm.ALARM_MON:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_MONDAY;
                    case Alarm.ALARM_TUE:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_TUESDAY;
                    case Alarm.ALARM_WED:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_WEDNESDAY;
                    case Alarm.ALARM_THU:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_THURSDAY;
                    case Alarm.ALARM_FRI:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_FRIDAY;
                    case Alarm.ALARM_SAT:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_SATURDAY;
                    case Alarm.ALARM_SUN:
                        repetition |= TLW64Constants.ARG_SET_ALARM_REMINDER_REPEAT_SUNDAY;
                        break;

                    default:
                        LOG.warn("invalid alarm repetition " + alarm.getRepetition());
                        break;
                }

                byte[] alarmMessage = new byte[]{
                        TLW64Constants.CMD_ALARM,
                        (byte) repetition,
                        (byte) calendar.get(Calendar.HOUR_OF_DAY),
                        (byte) calendar.get(Calendar.MINUTE),
                        (byte) (alarm.getEnabled() ? 2 : 0),    // vibration duration
                        (byte) (alarm.getEnabled() ? 10 : 0),   // vibration count
                        (byte) (alarm.getEnabled() ? 2 : 0),    // unknown
                        (byte) 0,
                        (byte) (alarm.getPosition() + 1)
                };
                builder.write(ctrlCharacteristic, alarmMessage);
            }
            builder.queue(getQueue());
            if (anyAlarmEnabled) {
                GB.toast(getContext(), getContext().getString(R.string.user_feedback_miband_set_alarms_ok), Toast.LENGTH_SHORT, GB.INFO);
            } else {
                GB.toast(getContext(), getContext().getString(R.string.user_feedback_all_alarms_disabled), Toast.LENGTH_SHORT, GB.INFO);
            }
        } catch (IOException ex) {
            GB.toast(getContext(), getContext().getString(R.string.user_feedback_miband_set_alarms_failed), Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (callSpec.command == CallSpec.CALL_INCOMING) {
            showNotification(TLW64Constants.NOTIFICATION_CALL, callSpec.name);
            setVibration(3, 5);
        } else {
            stopNotification();
            setVibration(0, 0);
        }
    }

    @Override
    public void onSetCannedMessages(CannedMessagesSpec cannedMessagesSpec) {

    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {

    }

    @Override
    public void onSetMusicInfo(MusicSpec musicSpec) {

    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {

    }

    @Override
    public void onInstallApp(Uri uri) {

    }

    @Override
    public void onAppInfoReq() {

    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {

    }

    @Override
    public void onAppDelete(UUID uuid) {

    }

    @Override
    public void onAppConfiguration(UUID appUuid, String config, Integer id) {

    }

    @Override
    public void onAppReorder(UUID[] uuids) {

    }

    @Override
    public void onFetchRecordedData(int dataTypes) {

    }

    @Override
    public void onReset(int flags) {
        if (flags == GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET){
            try {
                TransactionBuilder builder = performInitialized("factoryReset");
                byte[] msg = new byte[]{
                        TLW64Constants.CMD_FACTORY_RESET,
                };
                builder.write(ctrlCharacteristic, msg);
                builder.queue(getQueue());
            } catch (IOException e) {
                GB.toast(getContext(), "Error during factory reset: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
            }
        }
    }

    @Override
    public void onHeartRateTest() {

    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {

    }

    @Override
    public void onFindDevice(boolean start) {
        if (start) {
            setVibration(1, 3);
        }
    }

    @Override
    public void onSetConstantVibration(int integer) {

    }

    @Override
    public void onScreenshotReq() {

    }

    @Override
    public void onEnableHeartRateSleepSupport(boolean enable) {

    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {

    }

    @Override
    public void onAddCalendarEvent(CalendarEventSpec calendarEventSpec) {

    }

    @Override
    public void onDeleteCalendarEvent(byte type, long id) {

    }

    @Override
    public void onSendConfiguration(String config) {

    }

    @Override
    public void onReadConfiguration(String config) {

    }

    @Override
    public void onTestNewFunction() {

    }

    @Override
    public void onSendWeather(WeatherSpec weatherSpec) {

    }

    private void setVibration(int duration, int count) {
        try {
            TransactionBuilder builder = performInitialized("vibrate");
            byte[] msg = new byte[]{
                    TLW64Constants.CMD_ALARM,
                    0,
                    0,
                    0,
                    (byte) duration,
                    (byte) count,
                    7,                  // unknown, sniffed by original app
                    1
            };
            builder.write(ctrlCharacteristic, msg);
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("Unable to set vibration", e);
        }
    }

    private void setTime(TransactionBuilder transaction) {
        Calendar c = GregorianCalendar.getInstance();
        byte[] datetimeBytes = new byte[]{
                TLW64Constants.CMD_DATETIME,
                (byte) (c.get(Calendar.YEAR) / 256),
                (byte) (c.get(Calendar.YEAR) % 256),
                (byte) (c.get(Calendar.MONTH) + 1),
                (byte) c.get(Calendar.DAY_OF_MONTH),
                (byte) c.get(Calendar.HOUR_OF_DAY),
                (byte) c.get(Calendar.MINUTE),
                (byte) c.get(Calendar.SECOND)
        };
        transaction.write(ctrlCharacteristic, datetimeBytes);
    }

    private void setDisplaySettings(TransactionBuilder transaction) {
        byte[] displayBytes = new byte[]{
                TLW64Constants.CMD_DISPLAY_SETTINGS,
                0x00,   // 1 - display distance in kilometers, 2 - in miles
                0x00    // 1 - display 24-hour clock, 2 - for 12-hour with AM/PM
        };
        String units = GBApplication.getPrefs().getString(SettingsActivity.PREF_MEASUREMENT_SYSTEM, getContext().getString(R.string.p_unit_metric));
        if (units.equals(getContext().getString(R.string.p_unit_metric))) {
            displayBytes[1] = 1;
        } else {
            displayBytes[1] = 2;
        }
        if (DateFormat.is24HourFormat(getContext())) {
            displayBytes[2] = 1;
        } else {
            displayBytes[2] = 2;
        }
        transaction.write(ctrlCharacteristic, displayBytes);
        return;
    }

    private void sendSettings(TransactionBuilder builder) {
        // TODO Create custom settings page for changing hardcoded values

        // set user data
        ActivityUser activityUser = new ActivityUser();
        byte[] userBytes = new byte[]{
                TLW64Constants.CMD_USER_DATA,
                0,  // unknown
                0,  // step length [cm]
                0,  // unknown
                (byte) activityUser.getWeightKg(),
                5,  // screen on time / display timeout
                0,  // unknown
                0,  // unknown
                (byte) (activityUser.getStepsGoal() / 256),
                (byte) (activityUser.getStepsGoal() % 256),
                1,  // raise hand to turn on screen, ON = 1, OFF = 0
                (byte) 0xff, // unknown
                0,  // unknown
                (byte) activityUser.getAge(),
                0,  // gender
                0,  // lost function, ON = 1, OFF = 0 TODO: find out what this does
                2   // unknown
        };

        if (activityUser.getGender() == ActivityUser.GENDER_FEMALE)
        {
            userBytes[14] = 2; // female
            // default and factor from https://livehealthy.chron.com/determine-stride-pedometer-height-weight-4518.html
            if (activityUser.getHeightCm() != 0)
                userBytes[2] = (byte) Math.ceil(activityUser.getHeightCm() * 0.413);
            else
                userBytes[2] = 70; // default
        }

        else
        {
            userBytes[14] = 1; // male
            if (activityUser.getHeightCm() != 0)
                userBytes[2] = (byte) Math.ceil(activityUser.getHeightCm() * 0.415);
            else
                userBytes[2] = 78; // default
        }

        builder.write(ctrlCharacteristic, userBytes);

        // device settings
        builder.write(ctrlCharacteristic, new byte[]{
                TLW64Constants.CMD_DEVICE_SETTINGS,
                (byte) 0x00,   // 1 - turns on inactivity alarm
                (byte) 0x3c,   // unknown, sniffed by original app
                (byte) 0x02,   // unknown, sniffed by original app
                (byte) 0x03,   // unknown, sniffed by original app
                (byte) 0x01,   // unknown, sniffed by original app
                (byte) 0x00    // unknown, sniffed by original app
        });
    }

    private void showIcon(int iconId) {
        try {
            TransactionBuilder builder = performInitialized("showIcon");
            byte[] msg = new byte[]{
                    TLW64Constants.CMD_ICON,
                    (byte) iconId
            };
            builder.write(ctrlCharacteristic, msg);
            builder.queue(getQueue());
        } catch (IOException e) {
            GB.toast(getContext(), "Error showing icon: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    private void showNotification(int type, String text) {
        try {
            TransactionBuilder builder = performInitialized("showNotification");
            int length;
            byte[] bytes;
            byte[] msg;

            // send text
            bytes = text.getBytes("EUC-JP");
            length = min(bytes.length, 18);
            msg = new byte[length + 2];
            msg[0] = TLW64Constants.CMD_NOTIFICATION;
            msg[1] = TLW64Constants.NOTIFICATION_HEADER;
            System.arraycopy(bytes, 0, msg, 2, length);
            builder.write(ctrlCharacteristic, msg);

            // send notification type
            msg = new byte[2];
            msg[0] = TLW64Constants.CMD_NOTIFICATION;
            msg[1] = (byte) type;
            builder.write(ctrlCharacteristic, msg);

            builder.queue(getQueue());
        } catch (IOException e) {
            GB.toast(getContext(), "Error showing notificaton: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    private void stopNotification() {
        try {
            TransactionBuilder builder = performInitialized("clearNotification");
            byte[] msg = new byte[]{
                    TLW64Constants.CMD_NOTIFICATION,
                    TLW64Constants.NOTIFICATION_STOP
            };
            builder.write(ctrlCharacteristic, msg);
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("Unable to stop notification", e);
        }
    }
}
